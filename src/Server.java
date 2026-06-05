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
            site.DefaultIndex = (getValeur(elem, "DefaultIndex") == null ? "index.html" : getValeur(elem, "DefaultIndex"));
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

    public static void main(String[] args) throws IOException {
        int port;
        if (args.length >= 1) port = Integer.parseInt(args[1]);
        else port=80;
        ServerSocket serv=new ServerSocket(port);
        while(true){
            Socket sock=serv.accept();
            BufferedReader br=new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line=br.readLine();
            String file=line.split(" ")[1];
            if (file.equals("/")){
                file="/index.html";
            }
            String ct=file.substring(file.lastIndexOf('.') + 1);
            if(ct.equals("gif")||ct.equals("png")||ct.equals("jpg")||ct.equals("jpeg")){
                file="/"+file;
                ct="image/"+ct;
            } else if (ct.equals("ico")) {
                file="/images/"+file;
                ct="image/x-icon";
            } else{
                ct="text/html";
            }
            File f=new File("../../fichiers du site web-20260507"+file);
            byte[] b=Files.readAllBytes(f.toPath());
            OutputStream os = sock.getOutputStream();
            os.write("HTTP/1.1 200 OK\r\n".getBytes());
            os.write(("Content-Type: "+ct+"\r\n").getBytes());
            os.write(("Content-Length: "+b.length+"\r\n").getBytes());
            os.write("\r\n".getBytes());
            os.write(b);
            sock.close();
            br.close();
        }
    }

    private static void handleClient(Socket clientSocket) {
        // Le bloc try-with-resources garantit la fermeture des flux
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            // 3. Lire la première ligne de la requête HTTP (ex: "GET /index.html HTTP/1.1")
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            System.out.println("Requête : " + requestLine);

            // 4. Construire la réponse HTTP
            // Les en-têtes (Headers)
            out.println("HTTP/1.1 200 OK"); // Code de succès
            out.println("Content-Type: text/html; charset=UTF-8"); // Type de contenu
            out.println("Connection: close"); // Demande au navigateur de fermer la connexion

            // LIGNE VIDE OBLIGATOIRE séparant les headers du corps de la page
            out.println();

            // Le corps de la réponse (Body)
            out.println("<html><head><title>Mon Serveur</title></head>");
            out.println("<body><h1>Bienvenue sur mon site !</h1>");
            out.println("<p>Vous avez demandé : <strong>" + requestLine.split(" ")[1] + "</strong></p>");
            out.println("</body></html>");

        } catch (IOException e) {
            System.err.println("Erreur avec le client : " + e.getMessage());
        } finally {
            // 5. Toujours refermer le socket pour libérer le port et le thread
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}