package org.fao.geonet.geocat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import jeeves.interfaces.Service;
import jeeves.server.ServiceConfig;
import jeeves.server.context.ServiceContext;
import jeeves.server.local.LocalServiceRequest;
import jeeves.server.sources.ServiceRequest.InputMethod;
import jeeves.utils.BinaryFile;
import jeeves.utils.Xml;

import org.fao.geonet.kernel.setting.SettingInfo;
import org.fao.geonet.services.Utils;
import org.jdom.Element;
import org.xhtmlrenderer.pdf.ITextRenderer;

public class Print implements Service {

	private static final String TMP_PDF_FILE = "Document";
	@Override
	public void init(String appPath, ServiceConfig params) throws Exception {
	}

	@Override
	public Element exec(Element params, ServiceContext context)
			throws Exception {
		String id = Utils.getIdentifierFromParameters(params, context);
		
		LocalServiceRequest request = LocalServiceRequest.create("metadata.show.embedded.print?id="+id+"&currTab=complete");
		request.setDebug(false);
		request.setLanguage(context.getLanguage());
		request.setInputMethod(InputMethod.GET);
		context.basicExecute(request);
		

        File tempDir = (File) context.getServlet().getServletContext().
        	       getAttribute( "javax.servlet.context.tempdir" );

        File tempFile = File.createTempFile(TMP_PDF_FILE, ".pdf", tempDir);
        OutputStream os = new FileOutputStream(tempFile);
        
        try {
        	String siteURL = new SettingInfo(context).getSiteUrl(false);
        	String baseURL = siteURL+"/"+context.getBaseUrl()+"/srv/eng/metadata.show.embedded.print";
	        ITextRenderer renderer = new ITextRenderer();
	        String resultString = request.getResultString();
			renderer.setDocumentFromString(resultString, baseURL);
	        renderer.layout();
	        renderer.createPDF(os);
        }
        finally {
        	os.close();
        }
        
        Element res = BinaryFile.encode(200, tempFile.getAbsolutePath(), true);
        return res;
	}

}
