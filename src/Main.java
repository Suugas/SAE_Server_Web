import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main {

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
            site.DefaultIndex = (getValeur(elem, "DefaultIndex") == null ? "var/www/index.html" : getValeur(elem, "DefaultIndex"));
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

    public List<Site> getSites() {
        return sites;
    }

    public static void main(String[] args) throws ParserConfigurationException, IOException, SAXException {
        Main main = new Main();
        main.Load();
        main.sites.forEach(System.out::println);
    }
}
