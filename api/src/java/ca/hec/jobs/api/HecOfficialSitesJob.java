package ca.hec.jobs.api;

import org.quartz.Job;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.site.api.Site;

/**
 * Created by mame-awa.diop@hec.ca on 2017-02-07.
 */
public interface HecOfficialSitesJob extends Job {

    public final static String SUMMER = "E";

    public final static String WINTER = "H";

    public final static String FALL = "A";

    public final static String HEC_TEMPLATE_SITE = "hec-template";


    public static final String MODE_ENSEIGNEMENT_EN_LIGNE = "WW";

    public static final String MODE_ENSEIGNEMENT_PRESENTIEL = "P";

    public static final String MODE_ENSEIGNEMENT_HYBRIDE = "AL";

    public static final String MODE_ENSEIGNEMENT_AUTRE = "IS";

    public Site createSite (CourseOffering courseOffering);

    public String getSiteName(CourseOffering courseOff);

    public String getSessionName(AcademicSession session);

}
