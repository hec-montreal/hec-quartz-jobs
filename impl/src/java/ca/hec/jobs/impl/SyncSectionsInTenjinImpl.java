package ca.hec.jobs.impl;

import ca.hec.jobs.api.SyncSectionsInTenjin;
import ca.hec.tenjin.api.SyllabusService;
import ca.hec.tenjin.api.dao.SyllabusDao;
import lombok.Setter;
import org.apache.log4j.Logger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.SessionManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by 11091096 on 2017-05-08.
 */
public class SyncSectionsInTenjinImpl implements SyncSectionsInTenjin {

    private static final Logger log = Logger.getLogger(SyncSectionsInTenjinImpl.class);

    @Setter
    protected CourseManagementService cmService;

    @Setter
    protected SessionManager sessionManager;

    @Setter
    protected SiteService siteService;

    @Setter
    protected SyllabusDao syllabusDao;

    @Setter
    protected SyllabusService syllabusService;

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        String startDate = jobExecutionContext.getMergedJobDataMap().getString("syncSectionsInTenjin.startDate");
/*
        Date startingDate = getDate(startDate);
        Date createdOn = null;
        int counter = 0;
        String providerId;
        Site site;
        List<String> providerIds;


        List<Site> allSites = siteService.getSites(SiteService.SelectionType.ANY, "course", null, null, SiteService.SortType.CREATED_ON_DESC, null);
        List<Syllabus> syllabi;
        Syllabus common = null;
        //Use groupIds
        List<String> syllabusSections, newSyllabusSections, oldSyllabusSections;
        Collection<Group> siteGroups = null;

        Session session = sessionManager.getCurrentSession();

        try {
            session.setUserEid("admin");
            session.setUserId("admin");

            do {
                site = allSites.get(counter++);
                createdOn = site.getCreatedDate();
                if (site.getId().equalsIgnoreCase("30-400-13.E2017")) {
                    if (createdOn.before(startingDate))
                        continue;
                    if (site.getProviderGroupId() == null || site.getProviderGroupId().isEmpty())
                        continue;
                    System.out.println(site.getProviderGroupId());
                    providerId = site.getProviderGroupId();
                    try {

                        providerIds = new ArrayList<String>();
                        Collections.addAll(providerIds, providerId.split("\\+"));

                        syllabi = syllabusService.getSyllabusList(site.getId());

                        //Make sure all the sections in Tenjin are up to date
                        //Delete cancelled sections
                        for (Syllabus syllabus : syllabi) {
                            if (syllabus.getCommon())
                                common = syllabus;
                            syllabusSections = syllabusDao.getSyllabusSections(syllabus.getId().toString());
                            newSyllabusSections = new ArrayList<String>();
                            oldSyllabusSections = new ArrayList<String>();
                            if (syllabusSections != null) {
                                for (String section : syllabusSections) {
                                    System.out.println("Section ou groupId " + section);
                                    /*if (providerIds.contains(section)) {
                                        newSyllabusSections.add(section);
                                        providerIds.remove(section);
                                    } else {
                                        oldSyllabusSections.add(section);
                                    }*/
                                //}
                               /* if (!providerIds.isEmpty()) {
                                    newSyllabusSections.addAll(providerIds);
                                }
                                for (String oldSectionId : oldSyllabusSections) {
                                    syllabusDao.deleteSection(syllabus.getId().toString(), oldSectionId);
                                }
                                System.out.println("To delete " + oldSyllabusSections.toString());*/
                            //}

                            //Add new sections to common
                           /* if (common != null)
                                for (String newSectionId : newSyllabusSections) {
                                    syllabusDao.addSection(common.getId().toString(), newSectionId);
                                }


                        }

                    } catch (NoSiteException e) {
                        //Will not happen but just in case
                        log.error(e.getMessage());
                    } catch (DeniedAccessException e) {
                        log.error("You are not allowed to access syllabi for the site " + site.getId() + " " + e.getMessage());
                    }
                }
            } while (createdOn.after(startingDate));
        } finally {
            session.clear();
        }

*/
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
