package qaengine.platform.otp;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;

/**
 * IMAP over TLS: IMAPS, or STARTTLS that must succeed. Only envelopes of mail received since the request
 * are fetched; the body of a candidate is read only to find the code in it, and goes nowhere else.
 */
@org.springframework.stereotype.Component
public final class ImapOtpMailbox implements OtpMailbox {

    /** Mail servers and the platform rarely agree to the second. */
    static final Duration CLOCK_ALLOWANCE = Duration.ofSeconds(30);
    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_BODY_CHARS = 100_000;

    private final Map<String, String> settings;

    public ImapOtpMailbox() {
        this(Map.of());
    }

    /** @param settings extra Jakarta Mail settings; tests use this to trust a local test server's certificate */
    ImapOtpMailbox(Map<String, String> settings) {
        this.settings = Map.copyOf(settings);
    }

    @Override
    public Result read(Request r) {
        boolean imaps = r.security().equals("IMAPS");
        String protocol = imaps ? "imaps" : "imap";
        Properties p = new Properties();
        p.put("mail.store.protocol", protocol);
        p.put("mail." + protocol + ".connectiontimeout", "10000");
        p.put("mail." + protocol + ".timeout", "10000");
        p.put("mail." + protocol + ".writetimeout", "10000");
        p.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        if (!imaps) {
            // STARTTLS or nothing: the password never crosses the network in the clear.
            p.put("mail.imap.starttls.enable", "true");
            p.put("mail.imap.starttls.required", "true");
        }
        p.putAll(settings);
        Session session = Session.getInstance(p);
        try (Store store = session.getStore(protocol)) {
            store.connect(r.host(), r.port(), r.username(), r.password());
            Folder folder = store.getFolder(r.folder());
            if (!folder.exists()) return new Failed("The mailbox folder does not exist");
            folder.open(Folder.READ_ONLY);
            try {
                return find(folder, r);
            } finally {
                folder.close(false);
            }
        } catch (AuthenticationFailedException e) {
            return new Failed("The mail server refused the mailbox sign-in");
        } catch (MessagingException e) {
            return new Failed("The mailbox could not be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private Result find(Folder folder, Request r) throws MessagingException {
        Instant earliest = r.since().minus(CLOCK_ALLOWANCE);
        // The server is asked only for recent mail: IMAP searches by day, and servers differ in how they match
        // senders and subjects. The exact time and the filters are checked here, on envelopes alone.
        SearchTerm term = new ReceivedDateTerm(ComparisonTerm.GE, Date.from(earliest));
        Message[] found = folder.search(term);
        FetchProfile envelope = new FetchProfile();
        envelope.add(FetchProfile.Item.ENVELOPE);
        envelope.add("Message-ID");
        folder.fetch(found, envelope);

        List<Message> candidates = new ArrayList<>();
        for (Message m : found) {
            Date received = m.getReceivedDate();
            if (received == null || received.toInstant().isBefore(earliest)) continue;
            if (!matches(m, r)) continue;
            candidates.add(m);
        }
        candidates.sort(Comparator.comparing((Message m) -> receivedOrEpoch(m)).reversed());
        for (Message m : candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()))) {
            String hash = hash(m);
            if (r.consumed().contains(hash)) continue;
            String body;
            try {
                body = text(m);
            } catch (IOException e) {
                return new Unreadable("A matching email arrived but its text could not be read");
            }
            Matcher code = r.codePattern().matcher(body);
            if (!code.find()) return new Unreadable("A matching email arrived but the code pattern found nothing in it");
            String value = code.groupCount() >= 1 && code.group(1) != null ? code.group(1) : code.group();
            return new Found(value.strip(), hash);
        }
        return new NotYet();
    }

    /** Sender and subject, as parts of the address and subject, ignoring case. Read from the envelope only. */
    private static boolean matches(Message m, Request r) throws MessagingException {
        if (!r.subjectFilter().isBlank()) {
            String subject = m.getSubject();
            if (subject == null || !subject.toLowerCase(Locale.ROOT).contains(r.subjectFilter().toLowerCase(Locale.ROOT))) return false;
        }
        if (!r.senderFilter().isBlank()) {
            var from = m.getFrom();
            String wanted = r.senderFilter().toLowerCase(Locale.ROOT);
            if (from == null || Arrays.stream(from).noneMatch(a -> (a instanceof InternetAddress ia ? ia.getAddress() : a.toString()).toLowerCase(Locale.ROOT).contains(wanted))) return false;
        }
        return true;
    }

    private static Date receivedOrEpoch(Message m) {
        try {
            Date d = m.getReceivedDate();
            return d == null ? new Date(0) : d;
        } catch (MessagingException e) {
            return new Date(0);
        }
    }

    /** The Message-ID when there is one; otherwise when it arrived and its subject. Hashed, never stored as is. */
    static String hash(Message m) throws MessagingException {
        String[] ids = m.getHeader("Message-ID");
        String key = ids != null && ids.length > 0 ? ids[0] : receivedOrEpoch(m).getTime() + "|" + m.getSubject();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Plain text preferred; HTML reduced to its text. Capped, because only a code is being looked for. */
    static String text(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) return cap(String.valueOf(part.getContent()));
        if (part.isMimeType("text/html")) return cap(String.valueOf(part.getContent()).replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ").replace("&nbsp;", " "));
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart mp) {
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part child = mp.getBodyPart(i);
                if (child.isMimeType("text/plain")) return text(child);
                if (html == null && (child.isMimeType("text/html") || child.isMimeType("multipart/*"))) html = text(child);
            }
            return html == null ? "" : html;
        }
        return "";
    }

    private static String cap(String s) {
        return s.length() > MAX_BODY_CHARS ? s.substring(0, MAX_BODY_CHARS) : s;
    }
}
