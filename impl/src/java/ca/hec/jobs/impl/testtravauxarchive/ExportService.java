package ca.hec.jobs.impl.testtravauxarchive;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.sakaiproject.tool.assessment.contentpackaging.ManifestGenerator;
import org.sakaiproject.tool.assessment.facade.AssessmentFacade;
import org.sakaiproject.tool.assessment.qti.util.XmlUtil;
import org.sakaiproject.tool.assessment.services.assessment.AssessmentService;
import org.sakaiproject.tool.assessment.services.qti.QTIService;
import org.w3c.dom.Document;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExportService {

    public void extractAssessment(String assessmentId, OutputStream outputStream) {
		//update random question pools (if any) before exporting
		AssessmentService assessmentService = new AssessmentService();
        AssessmentFacade a = assessmentService.getAssessment(assessmentId);
		int success = assessmentService.updateAllRandomPoolQuestions(a);
		if(success == AssessmentService.UPDATE_SUCCESS){

            ZipOutputStream zos = null;
            ZipEntry ze = null;

            try {
                byte[] b = null;
                
                zos = new ZipOutputStream(outputStream);

                // QTI file
                ze = new ZipEntry("exportAssessment.xml");
                zos.putNextEntry(ze);

                QTIService qtiService = new QTIService();
                log.debug("XMLController.assessment() assessment " +
                                    "qtiService.getExportedAssessment(id=" + assessmentId +
                                    ", qtiVersion=1)");
                Document doc = qtiService.getExportedAssessment(assessmentId, 1 /*qti version*/);
                String qtiString = XmlUtil.getDOMString(doc);
                
                log.debug("qtiString = " + qtiString);
                b = qtiString.getBytes();
                zos.write(b, 0, b.length);
                zos.closeEntry();

                // imsmanifest.xml
                ze = new ZipEntry("imsmanifest.xml");
                zos.putNextEntry(ze);
                ManifestGenerator manifestGenerator = new ManifestGenerator(
                        assessmentId);
                String manString = manifestGenerator.getManifest();
                log.debug("manString = " + manString);
                b = manString.getBytes();
                zos.write(b, 0, b.length);
                zos.closeEntry();

                // Attachments
                HashMap contentMap = manifestGenerator.getContentMap();

                String filename = null;
                for (Iterator it = contentMap.entrySet().iterator(); it.hasNext();) {
                    Map.Entry entry = (Map.Entry) it.next();
                    filename = (String)  entry.getKey();
                    ze = new ZipEntry(filename.substring(1));
                    zos.putNextEntry(ze);
                    b = (byte[]) entry.getValue();
                    zos.write(b, 0, b.length);
                    zos.closeEntry();
                }

            } catch (IOException e) {
                log.error(e.getMessage());
                return;
            } finally {
                if (zos != null) {
                    try {
                        zos.closeEntry();
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                    try {
                        zos.close();
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }

                }
            }

		}else{
			if(success == AssessmentService.UPDATE_ERROR_DRAW_SIZE_TOO_LARGE) {
                log.error("update_pool_error_size_too_large");
			}else{
                log.error("update_pool_error_unknown");
			}
            return;
		}
    }
}
