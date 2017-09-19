package ca.hec.jobs.api.coursemanagement;

import org.quartz.Job;

/**
 * Created by 11091096 on 2017-04-25.
 */
public interface  HECCMSynchroJob  extends Job {

    public final static String SESSION_FILE = "session.dat";

    public final static String COURS_FILE = "cours.dat";

    public final static String ETUDIANT_FILE = "etudiant_cours3.dat";

    public final static String HORAIRES_FILE = "horaires_cours.dat";

    public final static String PROF_FILE = "prof_cours3.dat";

    public final static String SECRETAIRES_FILE = "secretaires_serv_ens.dat";

    public final static String SERV_ENS_FILE = "service_enseignement.dat";

    public final static String PROG_ETUD_FILE = "programme_etudes.dat";

    public final static String INSTRUCTION_MODE = "mode_enseignement.dat";

    public final static String CHARGE_FORMATION = "charge_formation.dat";

    public final static String REQUIREMENTS = "cours_exigence.dat";

    public final static String EXTRACTS_PATH_CONFIG_KEY =
            "coursemanagement.extract.files.path";

    public final static String ACTIVE_STATUS = "active";

    public final static String COORDONNATEUR_ROLE = "C";

    public final static String COORDONNATEUR_INSTRUCTOR_ROLE = "CI";

    public final static String CHARGE_FORMATION_ROLE = "FTL";

    /**
     * Grading process
     */
    public final static String GRADING_SCHEME = "Letter";

    /**
     * Status of all the students currently enrolled and active
     */
    public final static String ENROLLMENT_STATUS = "enrolled";

    /**
     * Mapping to the type of site asociated to this course in Sakai.
     */
    public final static String COURSE_OFF_STATUS = "course";

    /**
     * Special section associated to sharable sites.
     */
    public final static String SHARABLE_SECTION = "00";

    /**
     * Le programme du certificat
     */
    public final static String CERTIFICAT = "CERT";

    public final String PILOTE_A2017 = "2173";

    public final static String CANCELED_SECTION = "X";

    public final static String ACTIVE_SECTION = "A";

    public static final int MAX_TITLE_BYTE_LENGTH = 100;

    public static final String INSTRUCTOR_ROLE = "I";

    public static final String ENSEIGNANT_ROLE = "Enseignant";

    public static final String COORDINATOR_ROLE = "Coordonnateur";

    public static final String ENGLISH = "en";
    public static final String FRENCH  = "fr_CA";
    public static final String SPANISH = "es";

}
