package serveurWeb.bin;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class Server {

    // =========================================================
    //  Classe interne Site : Représente un bloc <site> du XML
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
    //  Format de date imposé par le protocole HTTP
    // =========================================================
    private static final DateTimeFormatter HTTP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    // =========================================================
    //  Étape 1 : Chargement de la configuration XML
    // =========================================================
    public void Load() throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        // Lecture du fichier XML (Attention aux chemins selon que vous êtes sous Windows ou Linux)
        Document doc = builder.parse(new File("src/serveurWeb/conf/serverWeb.conf.xml"));
        doc.getDocumentElement().normalize();

        // Enregistrement du PID (Numéro du processus) pour SystemD sous Linux
        try {
            String pid = java.lang.management.ManagementFactory
                    .getRuntimeMXBean()
                    .getName()
                    .split("@")[0];

            String pidPath = "src/serveurWeb/run/myweb.pid";
            FileWriter fw = new FileWriter(pidPath);
            fw.write(pid);
            fw.close();

            System.out.println("PID écrit dans : " + pidPath);
        } catch (Exception e) {
            System.out.println("Impossible d'écrire le PID : " + e.getMessage());
        }

        // Récupération de tous les sites configurés dans le XML
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

    // Utilitaire pour extraire facilement le texte d'une balise XML
    private String getValeur(Element parent, String balise) {
        NodeList liste = parent.getElementsByTagName(balise);
        if (liste.getLength() == 0) return null;
        return liste.item(0).getTextContent().trim();
    }

    // =========================================================
    //  Étape 2 : Fonctions utilitaires (Cache & Dates)
    // =========================================================

    // Génère une empreinte unique (MD5) pour vérifier si un fichier a changé
    private static String computeETag(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder("\"");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            sb.append("\"");
            return sb.toString();
        } catch (Exception e) {
            return "\"0\"";
        }
    }

    // Convertit un temps (en millisecondes) au format texte HTTP
    private static String toHttpDate(long timestampMillis) {
        return HTTP_DATE_FORMAT.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneOffset.UTC)
        );
    }

    // =========================================================
    //  Étape 3 : Code Dynamique (Exécution de scripts externes)
    // =========================================================
    private static String processCodeTags(String html) {
        // Cherche la balise <code> avec l'attribut "interpreteur"
        Pattern pattern = Pattern.compile(
                "<code\\s+interpreteur=[\"«]([^\"»]+)[\"»]>(.*?)</code>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(html);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String interpreteur = matcher.group(1).trim();
            String code         = matcher.group(2).trim();

            String output = executeCode(interpreteur, code);
            // Remplace la balise par le résultat de l'exécution
            matcher.appendReplacement(result, Matcher.quoteReplacement(output));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String executeCode(String interpreteur, String code) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

            // Adaptation des chemins Linux vers Windows pour faciliter le dev local
            if (isWindows) {
                interpreteur = interpreteur
                        .replace("/usr/bin/python3", "python")
                        .replace("/usr/bin/python",  "python")
                        .replace("/bin/python3",     "python")
                        .replace("/bin/python",      "python")
                        .replace("/bin/bash",        "cmd.exe /c"); // Basique pour test Windows
            }

            File tempFile = File.createTempFile("dyncode_", ".py");
            tempFile.deleteOnExit();
            try (FileWriter fw = new FileWriter(tempFile, java.nio.charset.StandardCharsets.UTF_8)) {
                fw.write(code);
            }

            ProcessBuilder pb = new ProcessBuilder(interpreteur, tempFile.getAbsolutePath());
            pb.redirectErrorStream(true);

            if (isWindows) {
                pb.environment().put("PYTHONUTF8", "1");
                pb.environment().put("PYTHONIOENCODING", "utf-8");
            }

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();
            tempFile.delete();
            return output.toString().trim();

        } catch (Exception e) {
            return "[Erreur exécution : " + e.getMessage() + "]";
        }
    }

    // =========================================================
    //  Étape 4 : Compression (GZIP)
    // =========================================================
    private static byte[] compressGzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    // =========================================================
    //  MAIN : Point d'entrée de l'application
    // =========================================================
    public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException {
        Server server = new Server();
        server.Load(); // Charge le fichier serverWeb.conf.xml

        // Pour chaque site, on crée un thread (fil d'exécution) dédié
        for (Site s : server.sites) {
            int port = s.port;
            ServerSocket serv = new ServerSocket(port);
            System.out.println("Site en écoute sur le port " + port + " → " + s.DocumentRoot);

            new Thread(() -> {
                while (true) {
                    try {
                        Socket sock = serv.accept(); // Attend une connexion

                        // Dès qu'un client se connecte, on lance un thread pour le traiter
                        // Cela permet d'accepter d'autres clients sans bloquer le serveur
                        new Thread(() -> {
                            try {
                                Server.handleClient(sock, s);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }).start();

                    } catch (IOException e) {
                        System.err.println("Erreur de connexion client : " + e.getMessage());
                    }
                }
            }).start();
        }
    }

    // =========================================================
    //  Cœur du serveur : Traitement de la requête du client
    // =========================================================
    private static void handleClient(Socket clientSocket, Site site) throws IOException {

        BufferedReader br = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        // --- 1. Lecture de la requête HTTP (ex: GET /index.html HTTP/1.1) ---
        String requestLine = br.readLine();
        if (requestLine == null || requestLine.trim().isEmpty()) {
            clientSocket.close();
            return;
        }

        // --- 2. Lecture des en-têtes pour le Cache (ETag) et la Compression (Gzip) ---
        String ifNoneMatch     = null;
        String ifModifiedSince = null;
        String acceptEncoding  = null;

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

        String file = requestLine.split(" ")[1];
        System.out.println("Requête sur le port " + clientSocket.getPort() + " : " + file);

        // --- 3. Routage spécial (Page Status & Racine) ---
        if (file.equals("/status")){
            creerFichierStatus(clientSocket);
            return;
        } else if (file.equals("/")) {
            if (site.DefaultIndex == null || site.DefaultIndex.isEmpty()) {
                sendDirectoryListing(clientSocket, site, "/");
                return;
            } else {
                file = site.DefaultIndex.startsWith("/") ? site.DefaultIndex : "/" + site.DefaultIndex;
            }
        }

        // --- 4. Détermination du Type MIME ---
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

        File f = new File(site.DocumentRoot + file);

        try {
            // Lecture du fichier physique
            byte[] b = Files.readAllBytes(f.toPath());

            // --- 5. Code dynamique ---
            // Si c'est une page HTML, on cherche d'éventuelles balises <code> à exécuter
            if (ct.equals("text/html")) {
                String htmlContent = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                String processed   = processCodeTags(htmlContent);
                b = processed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            OutputStream os = clientSocket.getOutputStream();

            // Enregistrement dans les logs d'accès
            String mess = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> " + file + " (" + ct + ")\n";
            try (FileWriter fw = new FileWriter(new File(site.Acceslog), true)) {
                fw.write(mess);
            }

            // --- 6. Gestion du Cache (ETag & Last-Modified) ---
            String etag = computeETag(b);
            String lastModified = toHttpDate(f.lastModified());

            if (etag.equals(ifNoneMatch)) {
                send304(os, etag);
                clientSocket.close();
                return;
            }

            if (ifModifiedSince != null) {
                try {
                    ZonedDateTime clientDate = ZonedDateTime.parse(ifModifiedSince, HTTP_DATE_FORMAT);
                    ZonedDateTime fileDate   = ZonedDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()), ZoneOffset.UTC);
                    if (!fileDate.isAfter(clientDate)) {
                        send304(os, etag);
                        clientSocket.close();
                        return;
                    }
                } catch (Exception ignored) {}
            }

            // --- 7. Gestion de la Compression (GZIP) ---
            boolean clientSupportsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");
            boolean isCompressible     = ct.equals("image/png") || ct.equals("image/jpeg") || ct.equals("application/pdf") || ct.equals("text/html");

            byte[] responseBody = b;
            boolean compressed  = false;

            if (clientSupportsGzip && isCompressible) {
                responseBody = compressGzip(b);
                compressed   = true;
            }

            // --- 8. Envoi de la Réponse Finale HTTP 200 ---
            os.write("HTTP/1.1 200 OK\r\n".getBytes());

            // CORRECTION: Ajout des paramètres de base demandés par la SAÉ
            os.write(("Server: MonServeurJavaSAE/1.0\r\n").getBytes());
            os.write(("Date: " + toHttpDate(System.currentTimeMillis()) + "\r\n").getBytes());

            os.write(("Content-Type: "   + ct                   + "\r\n").getBytes());
            os.write(("Content-Length: " + responseBody.length  + "\r\n").getBytes());
            os.write(("ETag: "           + etag                 + "\r\n").getBytes());
            os.write(("Last-Modified: "  + lastModified         + "\r\n").getBytes());
            if (compressed) {
                os.write("Content-Encoding: gzip\r\n".getBytes());
            }
            os.write("\r\n".getBytes()); // Ligne vide qui sépare les en-têtes du corps
            os.write(responseBody);      // Le contenu du fichier (ou de la page)

        } catch (Exception e) {
            // Fichier non trouvé ou erreur de lecture -> Erreur 404
            String errorMessage = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> ERROR 404 on "  + file + '\n';
            try (FileWriter fw = new FileWriter(new File(site.Errorlog), true)) {
                fw.write(errorMessage);
            }
            send404(clientSocket);
        } finally {
            clientSocket.close();
        }
    }

    // =========================================================
    //  Page de statut du serveur (URL : /status)
    // =========================================================
    public static void creerFichierStatus(Socket clientSocket) throws IOException {
        OutputStream os = clientSocket.getOutputStream();

        // Calculs pour l'état du serveur
        long memLibreMo = Runtime.getRuntime().freeMemory() / 1024 / 1024;

        // CORRECTION: Calcul de l'espace disque réel (et non du processeur)
        File racine = new File("/");
        long disqueLibreGo = racine.getUsableSpace() / 1024 / 1024 / 1024;

        int nbProcessus = Thread.activeCount();

        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"utf-8\" /><title>Statut du Serveur</title></head><body>");
        html.append("<h1>État de la machine</h1><ul>");
        html.append("<li>Mémoire libre allouée à la JVM : ").append(memLibreMo).append(" Mo</li>");
        html.append("<li>Espace disque disponible : ").append(disqueLibreGo).append(" Go</li>");
        html.append("<li>Nombre de threads (processus) en cours : ").append(nbProcessus).append("</li>");
        html.append("</ul></body></html>");

        byte[] bBody = html.toString().getBytes();

        // CORRECTION: Passage en code HTTP 200 OK
        os.write("HTTP/1.1 200 OK\r\n".getBytes());
        os.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes());
        os.write(("Content-Length: " + bBody.length + "\r\n").getBytes());
        os.write("\r\n".getBytes());
        os.write(bBody);

        clientSocket.close();
    }

    // =========================================================
    //  Générateurs de réponses HTTP standards (304 et 404)
    // =========================================================
    private static void send304(OutputStream os, String etag) throws IOException {
        os.write("HTTP/1.1 304 Not Modified\r\n".getBytes());
        os.write(("ETag: " + etag + "\r\n").getBytes());
        os.write("\r\n".getBytes());
        // La spécification HTTP dit qu'un 304 ne contient aucun corps (body)
    }

    private static void send404(Socket clientSocket) throws IOException {
        OutputStream os = clientSocket.getOutputStream();
        String body = "<html><body><h1>404 Not Found</h1><p>La ressource n'existe pas.</p></body></html>";
        byte[] bBody = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        os.write("HTTP/1.1 404 Not Found\r\n".getBytes());
        os.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes());
        os.write(("Content-Length: " + bBody.length + "\r\n").getBytes());
        os.write("\r\n".getBytes());
        os.write(bBody);
    }

    // =========================================================
    //  Génération dynamique de la liste des fichiers (Mode Dossier)
    // =========================================================
    private static void sendDirectoryListing(Socket clientSocket, Site site, String urlPath) throws IOException {
        File dir = new File(site.DocumentRoot + urlPath);
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"UTF-8\"><title>Index de ").append(urlPath).append("</title></head><body>");
        html.append("<h1>Index de ").append(urlPath).append("</h1><hr><ul>");

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