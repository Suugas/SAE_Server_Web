import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;


public class Server {

    // =========================================================
    //  Classe interne Site
    // =========================================================
    public class Site {
        public int port;
        public String DocumentRoot;
        public String DefaultIndex;
        public String Acceslog;
        public String Errorlog;

        public String toString() {
            return DocumentRoot + "\n" + DefaultIndex + "\n" + Acceslog + "\n" + Errorlog;
        }
    }

    public List<Site> sites = new ArrayList<>();

    // =========================================================
    //  Constante de format de date HTTP (RFC 7231)
    // =========================================================
    private static final DateTimeFormatter HTTP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    // =========================================================
    //  Chargement du fichier de configuration XML
    // =========================================================
    public void Load() throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document doc = builder.parse(new File("src/serverWeb.conf.xml"));
        doc.getDocumentElement().normalize();

        NodeList listeSites = doc.getElementsByTagName("site");

        for (int i = 0; i < listeSites.getLength(); i++) {
            Element elem = (Element) listeSites.item(i);
            Site site = new Site();

            site.port         = Integer.parseInt(getValeur(elem, "port"));
            site.DocumentRoot = getValeur(elem, "DocumentRoot");
            site.DefaultIndex = getValeur(elem, "DefaultIndex");
            site.Acceslog     = getValeur(elem, "Acceslog");
            site.Errorlog     = getValeur(elem, "Errorlog");

            sites.add(site);
        }
    }

    // Utilitaire XML : lit le texte d'une balise (retourne null si absente)
    private String getValeur(Element parent, String balise) {
        NodeList liste = parent.getElementsByTagName(balise);
        if (liste.getLength() == 0) return null;
        return liste.item(0).getTextContent().trim();
    }

    // =========================================================
    //  UTILITAIRE CACHE — Calcule l'ETag MD5 du contenu
    // =========================================================
    private static String computeETag(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder("\"");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            sb.append("\"");
            return sb.toString(); // ex: "d41d8cd98f00b204e9800998ecf8427e"
        } catch (Exception e) {
            return "\"0\"";
        }
    }

    // =========================================================
    //  UTILITAIRE CACHE — Formate un timestamp en date HTTP
    // =========================================================
    private static String toHttpDate(long lastModifiedMillis) {
        return HTTP_DATE_FORMAT.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastModifiedMillis), ZoneOffset.UTC)
        );
    }

    // =========================================================
    //  UTILITAIRE GZIP — Compresse un tableau d'octets en gzip
    // =========================================================
    private static byte[] compressGzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    // =========================================================
    //  Point d'entrée
    // =========================================================
    public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException {
        Server server = new Server();
        server.Load();

        for (Site s : server.sites) {
            int port = s.port;
            ServerSocket serv = new ServerSocket(port);
            System.out.println("Site en écoute sur le port " + port + " → " + s.DocumentRoot);

            new Thread(() -> {
                while (true) {
                    try {
                        Socket sock = serv.accept();
                        Server.handleClient(sock, s);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }).start();
        }
    }

    // =========================================================
    //  Gestion d'une connexion client
    // =========================================================
    private static void handleClient(Socket clientSocket, Site site) throws IOException {

        BufferedReader br = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        // --- 1. Lire la ligne de requête ---
        String requestLine = br.readLine();
        if (requestLine == null || requestLine.trim().isEmpty()) {
            clientSocket.close();
            return;
        }

        // --- 2. Lire tous les en-têtes pour récupérer les infos de cache et d'encodage ---
        String ifNoneMatch     = null;
        String ifModifiedSince = null;
        String acceptEncoding  = null; // GZIP : ce que le client accepte

        String headerLine;
        while ((headerLine = br.readLine()) != null && !headerLine.isEmpty()) {
            if (headerLine.startsWith("If-None-Match:")) {
                ifNoneMatch = headerLine.substring("If-None-Match:".length()).trim();
            }
            if (headerLine.startsWith("If-Modified-Since:")) {
                ifModifiedSince = headerLine.substring("If-Modified-Since:".length()).trim();
            }
            if (headerLine.startsWith("Accept-Encoding:")) {
                acceptEncoding = headerLine.substring("Accept-Encoding:".length()).trim();
            }
        }

        // --- 3. Extraire le chemin demandé ---
        String file = requestLine.split(" ")[1];

        // --- 4. Gestion de la racine "/" ---
        if (file.equals("/")) {
            if (site.DefaultIndex == null) {
                // Pas d'index : on liste le répertoire
                sendDirectoryListing(clientSocket, site, "/");
                return;
            } else {
                file = site.DefaultIndex.startsWith("/") ? site.DefaultIndex : "/" + site.DefaultIndex;
            }
        }

        // --- 5. Déterminer le Content-Type ---
        String ct = "text/html";
        if (file.contains(".")) {
            String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase();
            switch (ext) {
                case "png":  ct = "image/png";         break;
                case "jpg":
                case "jpeg": ct = "image/jpeg";        break;
                case "pdf":  ct = "application/pdf";   break;
                case "gif":  ct = "image/gif";         break;
                case "ico":  ct = "image/x-icon";      break;
                case "css":  ct = "text/css";          break;
                case "js":   ct = "text/javascript";   break;
                default:     ct = "text/html";         break;
            }
        }

        // --- 6. Lire le fichier ---
        File f = new File(site.DocumentRoot + file);

        System.out.println("Info serveur: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).format(new Date(System.currentTimeMillis())) + " - Port : " + clientSocket.getPort());

        try {
            byte[] b = Files.readAllBytes(f.toPath());
            OutputStream os = clientSocket.getOutputStream();

            String mess = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> " + ct + '\n';
            File access = new File(site.Acceslog);
            FileWriter fw = new FileWriter(access, true);
            fw.write(mess);
            fw.close();

            // ==============================================
            //  GESTION DU CACHE
            // ==============================================

            // Calcul de l'ETag (empreinte MD5 du contenu)
            String etag = computeETag(b);

            // Date de dernière modification du fichier
            String lastModified = toHttpDate(f.lastModified());

            // -- Vérification If-None-Match (priorité sur If-Modified-Since) --
            if (etag.equals(ifNoneMatch)) {
                send304(os, etag);
                clientSocket.close();
                return;
            }

            // -- Vérification If-Modified-Since --
            if (ifModifiedSince != null) {
                try {
                    ZonedDateTime clientDate = ZonedDateTime.parse(ifModifiedSince, HTTP_DATE_FORMAT);
                    ZonedDateTime fileDate   = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(f.lastModified()), ZoneOffset.UTC);

                    if (!fileDate.isAfter(clientDate)) {
                        // Le fichier n'a pas été modifié depuis la date du client
                        send304(os, etag);
                        clientSocket.close();
                        return;
                    }
                } catch (Exception ignored) {
                    // Date mal formée envoyée par le client → on ignore et on sert le fichier
                }
            }

            // ==============================================
            //  GZIP — Compression si applicable
            // ==============================================
            boolean clientSupportsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");
            boolean isCompressible     = ct.equals("image/png")
                    || ct.equals("image/jpeg")
                    || ct.equals("application/pdf");

            byte[] responseBody = b;
            boolean compressed  = false;

            if (clientSupportsGzip && isCompressible) {
                responseBody = compressGzip(b);
                compressed   = true;
                System.out.println("GZIP appliqué sur : " + file
                        + " (" + b.length + " → " + responseBody.length + " octets)");
            }

            // ==============================================
            //  Réponse normale 200 OK
            // ==============================================
            os.write("HTTP/1.1 200 OK\r\n".getBytes());
            os.write(("Content-Type: "   + ct                   + "\r\n").getBytes());
            os.write(("Content-Length: " + responseBody.length  + "\r\n").getBytes());
            os.write(("ETag: "           + etag                 + "\r\n").getBytes());
            os.write(("Last-Modified: "  + lastModified         + "\r\n").getBytes());
            if (compressed) {
                os.write("Content-Encoding: gzip\r\n".getBytes()); // ← GZIP
            }
            os.write("\r\n".getBytes());
            os.write(responseBody);

        } catch (Exception e) {
            String errorMessage = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> "  + ct + '\n';
            // Charger les fichiers d'erreurs et d'accès
            File error = new File(site.Errorlog);
            FileWriter fw = new FileWriter(error, true);
            fw.write(errorMessage);
            fw.close();

            String mess = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> " + ct + '\n';
            File access = new File(site.Acceslog);
            FileWriter fg = new FileWriter(access, true);
            fg.write(mess);
            fg.close();

            System.err.println("Fichier non trouvé : " + f.getAbsolutePath());
            send404(clientSocket);
        } finally {
            clientSocket.close();
        }
    }

    // =========================================================
    //  Envoi d'un 304 Not Modified
    // =========================================================
    private static void send304(OutputStream os, String etag) throws IOException {
        os.write("HTTP/1.1 304 Not Modified\r\n".getBytes());
        os.write(("ETag: " + etag + "\r\n").getBytes());
        os.write("\r\n".getBytes());
        // Pas de corps dans un 304
    }

    // =========================================================
    //  Envoi d'un 404 Not Found
    // =========================================================
    private static void send404(Socket clientSocket) throws IOException {
        OutputStream os = clientSocket.getOutputStream();
        String body = "<html><body><h1>404 Not Found</h1></body></html>";
        byte[] bBody = body.getBytes();
        os.write("HTTP/1.1 404 Not Found\r\n".getBytes());
        os.write("Content-Type: text/html\r\n".getBytes());
        os.write(("Content-Length: " + bBody.length + "\r\n").getBytes());
        os.write("\r\n".getBytes());
        os.write(bBody);
    }

    // =========================================================
    //  Listing HTML d'un répertoire
    // =========================================================
    private static void sendDirectoryListing(Socket clientSocket, Site site, String urlPath) throws IOException {
        File dir = new File(site.DocumentRoot + urlPath);
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"UTF-8\"><title>Index de ")
                .append(urlPath).append("</title></head><body>");
        html.append("<h1>Index de ").append(urlPath).append(" (").append(site.DocumentRoot).append(")</h1><hr><ul>");

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File child : files) {
                    String name = child.getName();
                    if (child.isDirectory()) {
                        html.append("<li><a href=\"/").append(name).append("/\">📁 ").append(name).append("/</a></li>");
                    } else {
                        html.append("<li><a href=\"/").append(name).append("\">📄 ").append(name).append("</a></li>");
                    }
                }
            }
        } else {
            html.append("<li><em>Erreur : DocumentRoot introuvable.</em></li>");
        }
        html.append("</ul><hr></body></html>");

        byte[] bHtml = html.toString().getBytes();
        OutputStream os = clientSocket.getOutputStream();
        os.write("HTTP/1.1 200 OK\r\n".getBytes());
        os.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes());
        os.write(("Content-Length: " + bHtml.length + "\r\n").getBytes());
        os.write("\r\n".getBytes());
        os.write(bHtml);
        clientSocket.close();
    }
}
