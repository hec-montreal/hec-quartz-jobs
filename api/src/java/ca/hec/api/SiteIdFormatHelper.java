package ca.hec.api;

import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Section;

import java.util.List;
import java.util.Map;

/**
 *
 * Site Id or Site name are used because the site id is part of the site name
 *
 * Created by 11091096 on 2017-07-02.
 */
public interface SiteIdFormatHelper {
    public final static String SUMMER = "E";

    public final static String WINTER = "H";

    public final static String FALL = "A";

    public static final String MODE_ENSEIGNEMENT_EN_LIGNE = "WW";

    public static final String MODE_ENSEIGNEMENT_PRESENTIEL = "P";

    public static final String MODE_ENSEIGNEMENT_HYBRIDE = "AL";

    public static final String MODE_ENSEIGNEMENT_AUTRE = "IS";

    /**
     *  For a courseOffering return a list of siteName and the sections associated to the name.
     * @param courseOffering
     * @return Map<siteName, List<section>>
     */
    public Map<String, List<Section>> getSitesToCreateForCourseOffering(CourseOffering courseOffering);

    /**
     * The siteName associated to each section
     * @param courseOffering
     * @return Map<section, siteName>
     */
    Map<String, String> getSiteIds(CourseOffering courseOffering);

    /**
     * The siteName associated to the section
     * @param section
     * @return siteName
     */
    String getSiteId(Section section);

    /**
     * Generate siteId from data of extract files
     * @param catalog_nbr
     * @param session_id
     * @param session_code
     * @param instructionMode
     * @return siteId
     */
    String getSiteId(String catalog_nbr, String session_id, String session_code, String instructionMode);
}
