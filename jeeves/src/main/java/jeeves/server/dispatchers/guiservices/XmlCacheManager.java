package jeeves.server.dispatchers.guiservices;

import jeeves.XmlFileCacher;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.JeevesJCS;
import org.fao.geonet.utils.Log;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.springframework.stereotype.Component;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class XmlCacheManager {
    private static final String XML_FILE_CACHE_KEY = "XmlFile";
	Map<String, Map<String, XmlFileCacher>> eternalCaches = new HashMap<String, Map<String, XmlFileCacher>>();
    private Map<String, XmlFileCacher> getExternalCacheMap(boolean localized, String base, String file) {
        String key = localized+":"+base+":"+file;
        Map<String, XmlFileCacher> cacheMap = eternalCaches.get(key);
        if(cacheMap == null) {
            cacheMap = new HashMap<String, XmlFileCacher>(10);
            eternalCaches.put(key, cacheMap);
        }
        
        return cacheMap;
    }
    @SuppressWarnings("unchecked")
	private Map<String, XmlFileCacher> getVolatileCacheMap(boolean localized, String base, String file) {
    	try {
	    	JeevesJCS cache = JeevesJCS.getInstance(XML_FILE_CACHE_KEY);
	    	String key = localized+":"+base+":"+file;
	    	Map<String, XmlFileCacher> cacheMap = (Map<String, XmlFileCacher>) cache.get(key);
	    	if(cacheMap == null) {
	    		cacheMap = new HashMap<String, XmlFileCacher>(10);
				cache.put(key, cacheMap);
	    	}
	    	return cacheMap;
    	} catch (Exception e) {
    		Log.error(Log.JEEVES, "JeevesJCS cache not available, THIS IS NOT AN ERROR IF TESTING", e);
    		return getExternalCacheMap(localized, base, file);
    	}
    	
    }
    public synchronized Element get(ServiceContext context, boolean localized, String base, String file, String preferedLanguage, String defaultLang, boolean isExternal) throws JDOMException, IOException {

        Map<String, XmlFileCacher> cacheMap;
        
        if(isExternal) {
        	cacheMap = getExternalCacheMap(localized, base, file);
        } else {
        	cacheMap = getVolatileCacheMap(localized, base, file);
        }
        
        String appPath = context.getAppPath();
        String xmlFilePath;

        boolean isBaseAbsolutePath = (new File(base)).isAbsolute();
        String rootPath = (isBaseAbsolutePath) ? base : appPath + base;

        if (localized) {
            xmlFilePath = rootPath + File.separator + preferedLanguage +File.separator + file;
        } else {
            xmlFilePath = rootPath + File.separator + file;
            if (!new File(xmlFilePath).exists()) {
                xmlFilePath = appPath + file;
            }
        }

        ServletContext servletContext = null;
        if(context.getServlet() != null) {
            servletContext = context.getServlet().getServletContext();
        }
        
        XmlFileCacher xmlCache = cacheMap.get(preferedLanguage);
        File xmlFile = new File(xmlFilePath);
        if (xmlCache == null){
            xmlCache = new XmlFileCacher(xmlFile,servletContext,appPath);
            cacheMap.put(preferedLanguage, xmlCache);
        }

        Element result;
        try {
            result = (Element)xmlCache.get().clone();
        } catch (Exception e) {
            Log.error(Log.RESOURCES, "Error cloning the cached data.  Attempted to get: "+xmlFilePath+"but failed so falling back to default language");
            Log.debug(Log.RESOURCES, "Error cloning the cached data.  Attempted to get: "+xmlFilePath+"but failed so falling back to default language", e);
            String xmlDefaultLangFilePath = rootPath + File.separator + defaultLang + File.separator + file;
            xmlCache = new XmlFileCacher(new File(xmlDefaultLangFilePath),servletContext, appPath);
            cacheMap.put(preferedLanguage, xmlCache);
            result = (Element)xmlCache.get().clone();
        }
        String name = xmlFile.getName();
        int lastIndexOfDot = name.lastIndexOf('.');
        if (lastIndexOfDot > 0) {
            name = name.substring(0,lastIndexOfDot);
        }
        return result.setName(name);
    }

}
