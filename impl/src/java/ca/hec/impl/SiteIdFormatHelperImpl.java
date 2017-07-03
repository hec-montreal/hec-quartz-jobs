package ca.hec.impl;

import ca.hec.commons.utils.FormatUtils;
import ca.hec.api.SiteIdFormatHelper;
import lombok.Setter;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Section;

import java.util.*;

/**
 * Created by 11091096 on 2017-07-02.
 */
public class SiteIdFormatHelperImpl implements SiteIdFormatHelper {

    @Setter
    protected CourseManagementService cmService;

    @Override
    public Map<String, List<Section>> getSitesToCreateForCourseOffering(CourseOffering courseOffering) {
        Map<String, List<Section>> sitesToCreate = new HashMap<String, List<Section>>();
        Set<Section> sections = cmService.getSections(courseOffering.getEid());
        String siteName = null, siteNameAutre = null, siteNameEnligne = null, siteNameHybride = null;
        List<Section> assignedSections   = new ArrayList<Section>();
        List<Section> assignedSectionsAutre  = new ArrayList<Section>();
        List<Section> assignedSectionsEnligne  = new ArrayList<Section>();
        List<Section> assignedSectionsHybride  = new ArrayList<Section>();
        String sessionEid = courseOffering.getAcademicSession().getEid();

        for (Section section: sections){

            if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_AUTRE)) {
                siteNameAutre = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_AUTRE;
                assignedSectionsAutre.add(section);
            }
            else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_EN_LIGNE)){
                siteNameEnligne = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_EN_LIGNE;
                assignedSectionsEnligne.add(section);
            }
            else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_HYBRIDE)) {
                siteNameHybride = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_HYBRIDE;
                assignedSectionsHybride.add(section);
            }
            else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_PRESENTIEL)) {
                siteName = getSiteName(courseOffering);
                assignedSections.add(section);
            }
            else {
                siteName = getSiteName(courseOffering);
                assignedSections.add(section);
            }

        }

        if (assignedSectionsAutre.size() > 0)
            sitesToCreate.put(siteNameAutre, assignedSectionsAutre);
        if (assignedSectionsHybride.size() > 0)
            sitesToCreate.put(siteNameHybride, assignedSectionsHybride);
        if (assignedSectionsEnligne.size() > 0)
            sitesToCreate.put(siteNameEnligne, assignedSectionsEnligne);
        if (assignedSections.size() > 0)
            sitesToCreate.put(siteName, assignedSections);

        return sitesToCreate;

    }

    @Override
    public Map<String, String> getSiteIds(CourseOffering courseOffering) {
        Map<String, String> sectionsSiteName = new HashMap<String, String>();
        Set<Section> sections = cmService.getSections(courseOffering.getEid());
        String sessionEid = courseOffering.getAcademicSession().getEid();
        String siteName = null;

        for (Section section: sections){
            if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_AUTRE)) {
                siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_AUTRE;
                sectionsSiteName.put(section.getEid(), siteName);
            }
            else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_EN_LIGNE)){
                siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_EN_LIGNE;
                sectionsSiteName.put(section.getEid(), siteName);
            }
            else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_HYBRIDE)) {
                siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_HYBRIDE;
                sectionsSiteName.put(section.getEid(), siteName);
            }
            else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_PRESENTIEL)) {
                siteName = getSiteName(courseOffering);
                sectionsSiteName.put(section.getEid(), siteName);
            }
            else {
                siteName = getSiteName(courseOffering);
                sectionsSiteName.put(section.getEid(), siteName);
            }

        }

        return sectionsSiteName;
    }

    @Override
    public String getSiteId(Section section) {
        String siteName = null;
        String instructionMode = section.getInstructionMode();
        CourseOffering courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());
        String sessionEid = courseOffering.getAcademicSession().getEid();

        if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_AUTRE)) {
            siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_AUTRE;
        }
        else if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_EN_LIGNE)){
            siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_EN_LIGNE;
        }
        else if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_HYBRIDE)) {
            siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_HYBRIDE;
        }
        else if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_PRESENTIEL)) {
            siteName = getSiteName(courseOffering);
        }
        else {
            siteName = getSiteName(courseOffering);
        }
        return siteName;
    }

    @Override
    public String getSiteId(String catalog_nbr, String session_id, String session_code, String instructionMode) {
        String siteId = FormatUtils.formatCourseId(catalog_nbr);
        siteId += "." + FormatUtils.getSessionName(session_id);

        if (!session_code.equals("1"))
            siteId += "." + session_code;

        if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_AUTRE)) {
            siteId = siteId + "-" + MODE_ENSEIGNEMENT_AUTRE;
        }
        else if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_EN_LIGNE)){
            siteId = siteId +  "-" + MODE_ENSEIGNEMENT_EN_LIGNE;
        }
        else if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_HYBRIDE)) {
            siteId = siteId +  "-" + MODE_ENSEIGNEMENT_HYBRIDE;
        }
        else if (instructionMode.equalsIgnoreCase(MODE_ENSEIGNEMENT_PRESENTIEL)) {
            siteId = siteId ;
        }
        else {
            siteId = siteId ;
        }
        return null;
    }

    private String getSiteName(CourseOffering courseOff) {
        String siteName = null;
        String canCourseId = (courseOff.getCanonicalCourseEid()).trim();
        AcademicSession session = courseOff.getAcademicSession();
        String sessionId = session.getEid();

        String courseId = FormatUtils.formatCourseId(canCourseId);
        String sessionTitle = getSessionName(session);
        String periode = null;

        if (sessionId.matches(".*[pP].*")) {
            periode = sessionId.substring(sessionId.length() - 2);
        }

        if (periode == null)
            siteName = courseId + "." + sessionTitle;
        else
            siteName = courseId + "." + sessionTitle + "." + periode;

        return siteName;
    }


    private String getSessionName(AcademicSession session) {
        String sessionName = null;
        String sessionId = session.getEid();
        Date startDate = session.getStartDate();
        String year = startDate.toString().substring(0, 4);

        if ((sessionId.charAt(3)) == '1')
            sessionName = WINTER + year;
        if ((sessionId.charAt(3)) == '2')
            sessionName = SUMMER + year;
        if ((sessionId.charAt(3)) == '3')
            sessionName = FALL + year;

        return sessionName;
    }

}
