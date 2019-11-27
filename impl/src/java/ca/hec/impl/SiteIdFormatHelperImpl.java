package ca.hec.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Section;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.commons.utils.FormatUtils;
import lombok.Setter;

/**
 * Created by 11091096 on 2017-07-02.
 */
public class SiteIdFormatHelperImpl implements SiteIdFormatHelper {

    @Setter
    protected CourseManagementService cmService;

    @Override
    public Map<String, List<Section>> getSitesToCreateForCourseOffering(CourseOffering courseOffering, String distinctSitesSections) {
        Map<String, List<Section>> sitesToCreate = new HashMap<String, List<Section>>();
        Set<Section> sections = cmService.getSections(courseOffering.getEid());
        String siteName = null, siteNameAutre = null, siteNameEnligne = null, siteNameHybride = null;
        List<Section> assignedSections   = new ArrayList<Section>();
        List<Section> assignedSectionsAutre  = new ArrayList<Section>();
        List<Section> assignedSectionsEnligne  = new ArrayList<Section>();
        List<Section> assignedSectionsHybride  = new ArrayList<Section>();

        String[] distinctSectionsTitles = distinctSitesSections == null ? new String[0] : distinctSitesSections.split(",");
        
        for (Section section: sections){        	
        	String distinctTitle = getSectionDistinctTitle(section, distinctSectionsTitles);

        	if (distinctTitle != null) {
        		siteName = getSiteDistinctName(courseOffering, distinctTitle);
        		
        		if (!sitesToCreate.containsKey(siteName)) {
        			sitesToCreate.put(siteName, new ArrayList<>());
        		}

        		sitesToCreate.get(siteName).add(section);
        	} else {
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
        		 
                 if (assignedSectionsAutre.size() > 0)
                     sitesToCreate.put(siteNameAutre, assignedSectionsAutre);
                 if (assignedSectionsHybride.size() > 0)
                     sitesToCreate.put(siteNameHybride, assignedSectionsHybride);
                 if (assignedSectionsEnligne.size() > 0)
                     sitesToCreate.put(siteNameEnligne, assignedSectionsEnligne);
                 if (assignedSections.size() > 0)
                     sitesToCreate.put(siteName, assignedSections);
        	}
        }
        
        return sitesToCreate;

    }

    @Override
    public Map<String, String> getSiteIds(CourseOffering courseOffering, String distinctSitesSections) {
        Map<String, String> sectionsSiteName = new HashMap<String, String>();
        Set<Section> sections = cmService.getSections(courseOffering.getEid());

        for (Section section : sections) {
            sectionsSiteName.put(section.getEid(), getSiteId(section, distinctSitesSections));
        }

        return sectionsSiteName;
    }

    @Override
    public String getSiteId(Section section, String distinctSitesSections) {
        String siteName = null;
        CourseOffering courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());

        String[] distinctSectionsTitles = distinctSitesSections == null ? new String[0]
                : distinctSitesSections.split(",");

        String distinctTitle = getSectionDistinctTitle(section, distinctSectionsTitles);
        if (distinctTitle != null) {
            siteName = getSiteDistinctName(courseOffering, distinctTitle);
        } else {
            if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_AUTRE)) {
                siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_AUTRE;
            } else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_EN_LIGNE)) {
                siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_EN_LIGNE;
            } else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_HYBRIDE)) {
                siteName = getSiteName(courseOffering) + "-" + MODE_ENSEIGNEMENT_HYBRIDE;
            } else if (section.getInstructionMode().equalsIgnoreCase(MODE_ENSEIGNEMENT_PRESENTIEL)) {
                siteName = getSiteName(courseOffering);
            } else {
                siteName = getSiteName(courseOffering);
            }
        }
        return siteName;
    }

    @Override
    public String getSiteId(String catalog_nbr, String session_id, String session_code, String sectionName, String distinctSitesSections) {
        String sectionEid = catalog_nbr+session_id+session_code+sectionName;
        Section section = null;

        if (cmService.isSectionDefined(sectionEid))
            section = cmService.getSection(sectionEid);
        else
            return null;

        return getSiteId(section, distinctSitesSections);
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
    
    private String getSectionDistinctTitle(Section section, String[] distinctSectionsTitles) {
    	for (String title : distinctSectionsTitles) {
    		if (section.getTitle().startsWith(title)) {
    			return title;
    		}
    	}
    	
    	return null;
    }
    
    private String getSiteDistinctName(CourseOffering courseOffering, String distinctSectionTitle) {
    	return getSiteName(courseOffering) + "-" + "D" + distinctSectionTitle;
    }
}
