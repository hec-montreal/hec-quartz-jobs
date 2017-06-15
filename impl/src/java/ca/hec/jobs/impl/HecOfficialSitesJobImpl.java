package ca.hec.jobs.impl;

import ca.hec.commons.utils.FormatUtils;
import ca.hec.jobs.api.HecOfficialSitesJob;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.coursemanagement.api.*;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.exception.IdInvalidException;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.IdUsedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteAdvisor;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import org.sakaiproject.component.section.sakai.SectionManagerImpl;

/**
 * Created by mame-awa.diop@hec.ca on 2017-02-07.
 */

public class HecOfficialSitesJobImpl implements HecOfficialSitesJob {
    private static Log log = LogFactory.getLog(HecOfficialSitesJobImpl.class);

    @Setter
    protected CourseManagementAdministration cmAdmin;
    @Setter
    protected CourseManagementService cmService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String sessionStart = context.getMergedJobDataMap().getString("officialSitesSessionStart");
        String sessionEnd = context.getMergedJobDataMap().getString("officialSitesSessionEnd");
        String courses = context.getMergedJobDataMap().getString("officialSitesCourses");
        String programs = context.getMergedJobDataMap().getString("officialSitesPrograms");
        String departments = context.getMergedJobDataMap().getString("officialSitesDepartments");

        List<AcademicSession> selectedSessions = getSessions(sessionStart, sessionEnd);

        List<CourseOffering> selectedCO = getSelectedCO(selectedSessions, courses, programs, departments);

        Map<String, List<Section>> sitesToCreate;

        Session session = sessionManager.getCurrentSession();
        try {
            session.setUserEid("admin");
            session.setUserId("admin");
            for (CourseOffering courseOff: selectedCO){
                sitesToCreate = getSitesToCreateForCourseOffering(courseOff);
                sitesToCreate.forEach((siteName,sections) -> createSite(siteName, sections));
             }
        } finally {
            session.clear();
        }

    }


    private List<AcademicSession> getSessions (String sessionStart, String sessionEnd){
        List<AcademicSession> offSessions = new ArrayList<AcademicSession>();
        Date startDate = getDate(sessionStart);
        Date endDate = getDate(sessionEnd);
        List<AcademicSession> allSessions = cmService.getAcademicSessions();

        for(AcademicSession session: allSessions){
            if (session.getStartDate().compareTo(startDate) >= 0 && session.getEndDate().compareTo(endDate) <= 0)
                offSessions.add(session);
        }
        //Retrieve current sessions if no session
        if (offSessions.size() == 0) {
            offSessions = cmService.getCurrentAcademicSessions();
        }

        return offSessions;
    }


    private List<CourseOffering> getSelectedCO (List<AcademicSession> selectedSessions, String courses, String programs, String departments){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        String [] selectedCourses, selectedPrograms, selectedDepartments;


        if (programs != null && !programs.isEmpty()){
            selectedPrograms = splitProperty(programs);
            selectedCO.addAll(getSelectedCOByPrograms(selectedPrograms, selectedSessions));
        }

        if (departments != null && !departments.isEmpty()){
            selectedDepartments = splitProperty(departments);
            selectedCO.addAll(getSelectedCOByDepartments(selectedDepartments, selectedSessions));
        }

        if (courses != null && !courses.isEmpty()) {
            selectedCourses = splitProperty(courses);
            selectedCO.addAll(getSelectedCOByCourses(selectedCourses, selectedSessions));
        }
       return selectedCO;
    }

    private List<CourseOffering> getSelectedCOByDepartments ( String [] selectedDepartments, List<AcademicSession> offSessions){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        CourseSet department = null;
        for (int i=0; i< selectedDepartments.length; i++){
            department = cmService.getCourseSet(selectedDepartments[i]);
            if (department == null){
                log.warn(selectedDepartments[i] + " is not a valid department");
                continue;
            }
            for (AcademicSession session: offSessions){
                selectedCO.addAll(cmService.findCourseOfferings(department.getEid(), session.getEid()));
            }
        }


        return selectedCO;
    }

    private List<CourseOffering> getSelectedCOByPrograms ( String [] selectedPrograms, List<AcademicSession> offSessions){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        AcademicCareer acadCareer = null;
        for (int i=0; i<selectedPrograms.length; i++){
            acadCareer = cmService.getAcademicCareer(selectedPrograms[i]);
            if (acadCareer == null){
                log.warn( acadCareer + " is not a valid program");
                continue;
            }

            for (AcademicSession session: offSessions){
                selectedCO.addAll(cmService.findCourseOfferingsByAcadCareerAndAcademicSession(acadCareer.getEid(), session.getEid()));
            }

        }

        return selectedCO;
    }

    private List<CourseOffering> getSelectedCOByCourses ( String [] selectedCourses,List<AcademicSession> offSessions ){
        List<CourseOffering> selectedCO= new ArrayList<CourseOffering>();
        List<CourseOffering> tempCO =  new ArrayList<CourseOffering>();
        List<CanonicalCourse> cannCourses = getCanonicalCourses(selectedCourses);
        for (CanonicalCourse cann: cannCourses){
            tempCO.addAll(cmService.getCourseOfferingsInCanonicalCourse(cann.getEid()));
        }
        for( CourseOffering courseOff: tempCO){
            for (AcademicSession session: offSessions){
                if ((courseOff.getAcademicSession().getEid()).equals(session.getEid()))
                    selectedCO.add(courseOff);
            }
        }


        return selectedCO;
    }

    private String[] splitProperty (String property){
        if (property != null)
            return property.split(",");
        return null;
    }

    private List<CanonicalCourse> getCanonicalCourses(String[] courses){
        List<CanonicalCourse> offCanonicalCourses = new ArrayList<CanonicalCourse>();
        for (String course: courses){
            if (cmService.isCanonicalCourseDefined(course))
                offCanonicalCourses.add(cmService.getCanonicalCourse(course));
            else
                log.info (course + " is not a valid session eid");
        }
        return offCanonicalCourses;
    }

    private Site createSite (String siteName, List<Section> sections){
        try {
            Site createdSite = null;

            Site templateSite = siteService.getSite(HEC_TEMPLATE_SITE);

            if (!siteService.siteExists(siteName)) {
                createdSite = siteService.addSite(siteName, templateSite);
            }
            else
                createdSite = siteService.getSite(siteName);

            //Set site properties
            setSiteProperties(createdSite, siteName,  sections);

            //Associate to sections
            setProviderId(createdSite, sections);

            //Save3Update site properties, tools and providerId
            siteService.save(createdSite);

            //Update site membership
            List <SiteAdvisor> siteAdvisors = siteService.getSiteAdvisors();
            for (SiteAdvisor secManager: siteAdvisors)
                secManager.update(createdSite);

            return createdSite;
        } catch (IdUnusedException e) {
            log.error(HEC_TEMPLATE_SITE + " does not exist" + e.getMessage());
        } catch (IdUsedException e) {
           log.warn(siteName + "already exist");
        } catch (PermissionException e) {
            log.warn("You are not allowed to create " + siteName);
        } catch (IdInvalidException e) {
            log.error(HEC_TEMPLATE_SITE + " or " + siteName + " is not a valid siteId");
        }

        return null;
    }

    private void setSiteProperties (Site site, String siteName,  List<Section> sections){
        Section sectionRef = sections.get(0);
        CourseOffering courseOffering = cmService.getCourseOffering(sectionRef.getCourseOfferingEid());

        site.setTitle(siteName);
        site.setDescription(sectionRef.getDescription());

        ResourcePropertiesEdit rpe = site.getPropertiesEdit();
        rpe.addProperty(Site.PROP_SITE_TERM, courseOffering.getAcademicSession().getTitle());
        rpe.addProperty(Site.PROP_SITE_TERM_EID, courseOffering.getAcademicSession().getEid());
        rpe.addProperty("title", courseOffering.getTitle());

        if (courseOffering.getLang().equals("en"))
            rpe.addProperty("hec_syllabus_locale", "en_US");
        else if (courseOffering.getLang().equals("es"))
            rpe.addProperty("hec_syllabus_locale", "es_ES");
        else
            rpe.addProperty("hec_syllabus_locale", "fr_CA");
   }

   private void setProviderId (Site site, List<Section> sections){
       String providerGroupId = "";
       String sectionEid = null;
       for (Section section : sections) {
           updateSectionTitle(section);
           sectionEid = section.getEid();
           //TODO: Remove after tenjin deploy
           if (!sectionEid.isEmpty() && !sectionEid.endsWith("00") && !providerGroupId.contains(sectionEid)) {
               providerGroupId += section.getEid() + "+";
           }
           //TODO: Remove after tenjin deploy
           //Make sure coordinator is in course offering membership
           Section sectionRef = sections.get(0);
           CourseOffering courseOffering = cmService.getCourseOffering(sectionRef.getCourseOfferingEid());
           Set <Membership> courseOfferingCoordinator = cmService.getCourseOfferingMemberships(courseOffering.getEid());
           if (sectionEid.endsWith("00") || courseOfferingCoordinator.size() > 0){
               Set<Membership> coordinators  = cmService.getSectionMemberships(sectionEid);
               //Remove all section memberships
               for (Membership coordinator: coordinators){
                   for ( Section courseSection : sections) {
                       if (cmService.isSectionDefined(courseSection.getEid()))
                       cmAdmin.removeSectionMembership(coordinator.getUserId(), courseSection.getEid());
                   }
               }

           }
       }
       if(providerGroupId.endsWith("+"))
           providerGroupId = providerGroupId.substring(0, providerGroupId.lastIndexOf("+"));

       if (providerGroupId.length() > 0)
           site.setProviderGroupId(providerGroupId);
   }

    //TODO: Remove after tenjin deploy
   private void updateSectionTitle(Section section){
       AcademicSession session = cmService.getCourseOffering(section.getCourseOfferingEid()).getAcademicSession();
       String [] courseAndSection = (section.getEid()).split(session.getEid());
       if (courseAndSection.length == 2){
           section.setTitle(courseAndSection[1]);
           cmAdmin.updateSection(section);
       }

   }

   private Map<String, List<Section>> getSitesToCreateForCourseOffering(CourseOffering courseOffering){
       Map<String, List<Section>> sitesToCreate = new HashMap<String, List<Section>>();
       Set<Section> sections = cmService.getSections(courseOffering.getEid());
       String siteName = null, siteNameAutre = null, siteNameEnligne = null, siteNameHybride = null;
       List<Section> assignedSections   = new ArrayList<Section>();
       List<Section> assignedSectionsAutre  = new ArrayList<Section>();
       List<Section> assignedSectionsEnligne  = new ArrayList<Section>();
       List<Section> assignedSectionsHybride  = new ArrayList<Section>();

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

    private Date getDate(String date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Date convertedDate = null;
        try {
            convertedDate = dateFormat.parse(date);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return convertedDate;

    }

}
