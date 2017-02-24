
package ca.hec.sakai.extracts;


/**
 *
 */
public class Constants {

    public static final String ENGLISH = "en";
    public static final String FRENCH  = "fr_CA";
    public static final String SPANISH = "es";

    public static final int FINAL_DATE = 2173;

    public static boolean isCourseInDebug (String [] debugCourses, String coEid){
        for (String debugCourse: debugCourses ){
            if (coEid.contains(debugCourse))
                return true;
        }
        return false;
    }

    public static final boolean isInDateBound (int strm){
        return strm < FINAL_DATE ;
    }


	// Constructeur prive pour eviter l'instanciation
    private Constants () {
	}


}
