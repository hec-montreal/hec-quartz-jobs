package ca.hec.jobs.impl.testtravauxpdfjob;

import ca.hec.jobs.impl.AbstractQuartzJobImpl;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.assignment.api.AssignmentService;


import java.util.*;


public class HecTestTravauxPdfJob extends AbstractQuartzJobImpl {

    private static Log log = LogFactory.getLog(HecTestTravauxPdfJob.class);

    private static boolean isRunning = false;

    @Setter
    protected SiteService siteService;

    @Setter
    protected AssignmentService assignmentService;

    //protected AssessmentServiceAPI assessmentServiceAPI;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (isRunning) {
            log.warn("Stopping job since it's already running");
            return;
        }
        isRunning = true;

        //loginToSakai("CMOverrideSiteUsers");

        List<Site> sites = null;

        List<String> terms = getActiveTerms();


        String siteId = null;
        Collection<Assignment> assignments = null;




        try {
            for (int i = 0; i < terms.size(); i++) {

                Map<String, String> criteria = new HashMap<String, String>();
                criteria.put("term", terms.get(i));

                sites = siteService.getSites(SiteService.SelectionType.ANY, "course", null, criteria, SiteService.SortType.NONE, null);

                for (Site site : sites) {

                    siteId = site.getId();

                    log.info("sites" + siteId);


                    assignments = assignmentService.getAssignmentsForContext(siteId);
                    if (assignments == null)
                        continue;


                    for (Assignment assignment : assignments) {

                        try {

                            log.info("Assignment title" + assignment.getTitle() + "///");


                        } catch (Exception e1) {
                            e1.printStackTrace();
                        }

                    }
                }

            }

            log.info("The terms " + terms.toString() + " have been treated.");
            logoutFromSakai();
        } finally {
            isRunning = false;
        }
    }
}
