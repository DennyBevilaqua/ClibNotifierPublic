package clib.notifier;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Data
public class Application {

    private static final String LOGON_URL = "https://clib.pt/clib-login/";
    private static final String BASE_URL = "https://clib.pt/pt-pt/";
    private static final String INBOX_URL = "https://clib.pt/pt-pt/?dashboard=user&page=message&tab=inbox";

    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final int SMTP_PORT = 587;
    private static final String SMTP_USERNAME = "bevilaqua.eu@gmail.com";
    private static final String SMTP_PASSWORD = "iehmwxehrpefbnlr";
    private static final String SMTP_FROM_EMAIL = "bevilaqua.eu@gmail.com";

    private String username;
    private String password;
    private String emails;
    private String cookies;

    Application(String username, String password, String emails){
        this.username = username;
        this.password = password;
        this.emails = emails;
    }


    private ResponseEntity<String> logon(){
        RestTemplateBuilder builder = new RestTemplateBuilder();
        RestTemplate restTemplate = builder.build();

        HttpHeaders logonHeaders = new HttpHeaders();
        logonHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("log", username);
        map.add("pwd", password);

        HttpEntity<MultiValueMap<String, String>> logonRequest = new HttpEntity<MultiValueMap<String, String>>(map, logonHeaders);

        ResponseEntity<String> response = restTemplate.postForEntity(LOGON_URL, logonRequest, String.class);

        if (response.getStatusCode().is3xxRedirection() && response.getHeaders().containsKey("Set-Cookie")) {
            List<String> setCookies = response.getHeaders().get("Set-Cookie");
            for (String setCookie : setCookies) {
                if (cookies == null)
                    cookies = "";
                else
                    cookies = cookies + ";";

                cookies = cookies + setCookie;
            }
        }
        else
            cookies = null;

        return response;
    }

    private <T> ResponseEntity<T> requestHttp(String url, HttpMethod method, Class<T> cls){
        RestTemplateBuilder builder = new RestTemplateBuilder();
        RestTemplate restTemplate = builder.build();

        HttpHeaders inboxHeaders = new HttpHeaders();
        inboxHeaders.add("Cookie", cookies);

        HttpEntity<Void> inboxRequest = new HttpEntity<>(inboxHeaders);
        ResponseEntity<T> response = restTemplate.exchange(url, method, inboxRequest, cls);

        return response;
    }

    public void check() {
        try {
            ResponseEntity<String> loginResponse = logon();

            if (loginResponse.getStatusCode().is3xxRedirection() && loginResponse.getHeaders().containsKey("Set-Cookie")) {
                ResponseEntity<String> inboxResponse = requestHttp(INBOX_URL, HttpMethod.GET, String.class);

                if (inboxResponse.getStatusCode().is2xxSuccessful()) {
                    Message[] messages = readMessage(inboxResponse.getBody());

                    if(messages.length >0)
                        sendEmail(messages);
                    else{
                        System.out.println("No new messages");
                        System.exit(1);
                    }
                }
            } else {
                System.out.println(loginResponse.toString());
                throw new Exception("It was not possible to login");
            }

        } catch (Exception e) {
            System.out.println("Unexpected error");
            e.printStackTrace();

            try {
                sendEmail("Error reading CLIB website: " + e.getMessage());
            } catch (MessagingException ex) {
                System.out.println("Error sending email with error");
            }

            System.exit(-1);
        }
    }

    private void sendEmail(Message[] messages) throws MessagingException {
        StringBuffer body = new StringBuffer();
        for (Message msg: messages) {
            body.append("<b>");
            body.append(msg.getFrom());
            body.append("</b> (");
            body.append(msg.getDate());
            body.append("): ");
            body.append("<b>");
            body.append(msg.getSubject());
            body.append("</b><br/>");
            body.append(msg.getText());
            body.append("<br/><br/><hr/><br/>");
        }

        sendEmail(body.toString());
    }

    private void sendEmail(String body) throws MessagingException {

        Properties prop = new Properties();
        prop.put("mail.smtp.auth", true);
        prop.put("mail.smtp.starttls.enable", "true");
        prop.put("mail.smtp.host", SMTP_HOST);
        prop.put("mail.smtp.port", SMTP_PORT);
        prop.put("mail.smtp.ssl.trust", SMTP_HOST);

        Session session = Session.getInstance(prop, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USERNAME, SMTP_PASSWORD);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(SMTP_FROM_EMAIL));
        message.addRecipients(javax.mail.Message.RecipientType.TO, emails);
        message.setSubject("CLIB - Message Received");
        MimeBodyPart mimeBodyPart = new MimeBodyPart();
        mimeBodyPart.setContent(body, "text/html; charset=utf-8");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(mimeBodyPart);
        message.setContent(multipart);
        Transport.send(message);
    }

    private Message[] readMessage(String body) {
        Document html = Jsoup.parse(body);

        Elements mailboxContents = html.body().getElementsByClass("mailbox-content");

        if (mailboxContents.size() == 1) {
            Element mailboxContent = mailboxContents.get(0);
            Element tbody = null;

            try {

                Element mailboxTable = mailboxContent.firstElementChild().firstElementChild();
                Elements tbodyElements = mailboxTable.getElementsByTag("tbody");
                tbody = tbodyElements.get(0);
            }
            catch (Exception ex){
                ex.printStackTrace();
                throw new RuntimeException("Error reading mailbox html: " + ex.getMessage());
            }

            List<Message> result = new ArrayList<>();

            for (int i = 1; i < tbody.childrenSize(); i++) {
                Element mailboxRow = tbody.child(i);
                String from = mailboxRow.child(0).text();
                String subject = mailboxRow.child(2).getElementsByTag("a").text();
                String href = mailboxRow.child(2).getElementsByTag("a").attr("href");
                String text = mailboxRow.child(3).text();
                String status = mailboxRow.child(5).text();
                String date = mailboxRow.child(6).text();

                if (!status.equalsIgnoreCase("read")) {
                    Elements links = mailboxRow.child(4).getElementsByTag("a");

                    String attachmentsText = "";

                    for (Element link: links) {
                        String url = link.attr("href");

                        attachmentsText += url;
                        attachmentsText += "<br/>";
                    }

                    if(attachmentsText.length() >0) {
                        if (text == null)
                            text = "";

                        text += "<br/><br/>Attachments:<br/>" + attachmentsText;
                    }

                    markAsRead(href);

                    result.add(new Message(from, subject, text, date));
                }
            }

            return result.toArray(new Message[]{});
        } else {
            throw new RuntimeException("Found not one mailbox tag: " + mailboxContents.toString());
        }
    }

    private void markAsRead(String url){
        ResponseEntity<String> response = requestHttp(BASE_URL + url, HttpMethod.GET, String.class);
    }

    public static void main(String args[]) {
        new Application(args[0], args[1], args[2]).check();
    }
}
