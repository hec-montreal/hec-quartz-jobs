package ca.hec.jobs.impl.coursemanagement;

import lombok.Data;

/**
 * Created by 11091096 on 2017-07-11.
 */
@Data
public class InstructionMode{
    String instructionMode, descr, descrShort, descrAng, descrShortAng;

    public InstructionMode(String instructionMode, String descr, String descrShort, String descrAng, String descrShortAng){
        this.instructionMode = instructionMode;
        this.descr = descr;
        this.descrShort = descrShort;
        this.descrAng = descrAng;
        this.descrShortAng = descrShortAng;
    }

}
