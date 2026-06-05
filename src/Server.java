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
import java.util.ArrayList;
import java.util.List;


public class Server {

    public class Site {
        public int port;
        public String DocumentRoot;
        public String DefaultIndex;
        public String Acceslog;
        public String Errorlog;

        public String toString(){
            return DocumentRoot + "\n" + DefaultIndex + "\n" + Acceslog + "\n" + Errorlog;
        }
    }

    public List<Site> sites = new ArrayList<>();


    public void Load() throws ParserConfigurationException, IOException, SAXException {
        // 1. Créer le parser XML
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        // 2. Lire le fichier → obtenir un arbre DOM
        Document doc = builder.parse(new File("C:\\Users\\Albar\\OneDrive\\Documents\\Cour\\SAE\\S2\\Server Web\\SAE_Server_Web\\src\\serverWeb.conf.xml"));
        doc.getDocumentElement().normalize();

        // 3. Récupérer tous les noeuds <site>
        NodeList listeSites = doc.getElementsByTagName("site");

        for (int i = 0; i < listeSites.getLength(); i++) {
            Element elem = (Element) listeSites.item(i);
            Site site = new Site();

            // Lire chaque balise enfant
            site.port         = Integer.parseInt(getValeur(elem, "port"));
            site.DocumentRoot = getValeur(elem, "DocumentRoot");
            site.DefaultIndex = getValeur(elem, "DefaultIndex");
            site.Acceslog     = getValeur(elem, "Acceslog");
            site.Errorlog     = getValeur(elem, "Errorlog");

            sites.add(site);
        }
    }

    // Utilitaire : lit le texte d'une balise (retourne null si absente)
    private String getValeur(Element parent, String balise) {
        NodeList liste = parent.getElementsByTagName(balise);
        if (liste.getLength() == 0) return null;
        return liste.item(0).getTextContent().trim();
    }

    public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException {
        Server server = new Server();
        server.Load();

        for (Site s: server.sites) {
            int port = s.port;
            ServerSocket serv = new ServerSocket(port);
            new Thread(() -> {
                while (true) {
                    Socket sock = null;
                    try {
                        sock = serv.accept();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        Server.handleClient(sock, s);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

            }).start();
        }
    }

    private static void handleClient(Socket clientSocket, Site site) throws IOException {
        // 1. Un seul BufferedReader suffit
        BufferedReader br = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        // 2. On lit la première ligne et on la stocke immédiatement
        String line = br.readLine();

        // 3. On vérifie que la ligne n'est ni nulle, ni vide
        if (line == null || line.trim().isEmpty()) {
            clientSocket.close();
            return;
        }

        // 4. On extrait la route demandée (ex: "/" ou "/image.png")
        String file = line.split(" ")[1];

        // ====================================================================
        // NOUVELLE LOGIQUE : Gestion de la racine et de la liste des fichiers
        // ====================================================================
        if (file.equals("/")) {
            if (site.DefaultIndex == null) {
                // Cas où on n'a pas de fichier d'index : on liste le répertoire
                File dir = new File(site.DocumentRoot);
                StringBuilder html = new StringBuilder();

                html.append("<html><head><meta charset=\"UTF-8\"><title>Index de /</title></head><body>");
                html.append("<h1>Index de / (").append(site.DocumentRoot).append(")</h1><hr><ul>");


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
                    html.append("<li><em>Erreur : Le répertoire DocumentRoot n'existe pas ou n'est pas un dossier.</em></li>");
                }
                html.append("</ul><hr></body></html>");

                // On envoie directement la réponse HTML générée
                byte[] bHtml = html.toString().getBytes();
                OutputStream os = clientSocket.getOutputStream();
                os.write("HTTP/1.1 200 OK\r\n".getBytes());
                os.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes());
                os.write(("Content-Length: " + bHtml.length + "\r\n").getBytes());
                os.write("\r\n".getBytes());
                os.write(bHtml);

                clientSocket.close();
                return; // On arrête l'exécution ici !

            } else {
                // S'il y a un DefaultIndex, on remplace la racine "/" par ce fichier.
                // On s'assure qu'il y a bien un "/" au début.
                file = site.DefaultIndex.startsWith("/") ? site.DefaultIndex : "/" + site.DefaultIndex;
            }
        }
        // ====================================================================

        // Récupération de l'extension pour le Content-Type
        String ct = "text/html"; // Valeur par défaut
        if (file.contains(".")) {
            ct = file.substring(file.lastIndexOf('.') + 1);
            if (ct.equals("gif") || ct.equals("png") || ct.equals("jpg") || ct.equals("jpeg")) {
                ct = "image/" + ct;
            } else if (ct.equals("ico")) {
                ct = "image/x-icon";
            } else {
                ct = "text/html";
            }
        }

        // Création du chemin vers le fichier demandé
        File f = new File(site.DocumentRoot + file);

        try {
            String mess = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> " + ct + '\n';
            File access = new File(site.Acceslog);
            FileWriter fw = new FileWriter(access, true);
            fw.write(mess);
            fw.close();

            byte[] b = Files.readAllBytes(f.toPath());
            OutputStream os = clientSocket.getOutputStream();

            os.write("HTTP/1.1 200 OK\r\n".getBytes());
            os.write(("Content-Type: " + ct + "\r\n").getBytes());
            os.write(("Content-Length: " + b.length + "\r\n").getBytes());
            os.write("\r\n".getBytes()); // Ligne vide obligatoire

            // Envoi du contenu du fichier
            os.write(b);

        } catch (Exception e) {
            // Si le fichier n'est pas trouvé

            String errorMessage = clientSocket.getPort() + ": " + clientSocket.getInetAddress() + " --> "  + ct + '\n';
            // Charger les fichiers d'erreurs et d'accès
            File error = new File(site.Errorlog);
            FileWriter fw = new FileWriter(error, true);
            fw.write(errorMessage);
            fw.close();

            System.err.println("Fichier non trouvé : " + f.getAbsolutePath());
            OutputStream os = clientSocket.getOutputStream();
            String notFound = "<html><body><h1>404 Not Found</h1></body></html>";

            os.write("HTTP/1.1 404 Not Found\r\n".getBytes());
            os.write("Content-Type: text/html\r\n".getBytes());
            os.write(("Content-Length: " + notFound.getBytes().length + "\r\n").getBytes());
            os.write("\r\n".getBytes());
            os.write(notFound.getBytes());
        } finally {
            clientSocket.close();
        }
    }
}