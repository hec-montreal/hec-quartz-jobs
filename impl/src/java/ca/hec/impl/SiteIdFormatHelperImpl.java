package ca.hec.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sakaiproject.component.cover.ServerConfigurationService;
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
        List<Section> sections = new ArrayList<>(cmService.getSections(courseOffering.getEid()));

        // sort the list so we're always working with the same order
        Comparator<Section> c = new Comparator<Section>() {
            @Override
            public int compare(Section o1, Section o2) {
                return o1.getTitle().compareTo(o2.getTitle());
            }
        };
        sections.sort(c);

        for (Section section: sections) {
            String siteName = getSiteId(section, distinctSitesSections);
            if (!sitesToCreate.containsKey(siteName)) {
                sitesToCreate.put(siteName, new ArrayList<>());
            }

            sitesToCreate.get(siteName).add(section);
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
        String baseSiteName = getSiteName(courseOffering);

        Set<String> acceptedInstructionModes = 
            Stream.of(ServerConfigurationService.getString("hec.seperate.site.instructionModes", "").split(","))
            .collect(Collectors.toSet());

        String[] distinctSectionsTitles =
            distinctSitesSections == null ? new String[0] : distinctSitesSections.split(",");

        String distinctTitle = getSectionDistinctTitle(section, distinctSectionsTitles);
        String instructionMode = section.getInstructionMode();

        if (distinctTitle != null) {
            siteName = baseSiteName + "-D" + distinctTitle;
        } else if (acceptedInstructionModes.contains(instructionMode)) {
            siteName = baseSiteName + "-" + instructionMode;
        } else {
            siteName = baseSiteName;
        }
        return siteName;
    }

    
    public String buildSectionId(String catalog_nbr, String session_id, String session_code, String sectionName) {
        String sectionEid = catalog_nbr+session_id+session_code+sectionName;
        return sectionEid;
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
}
