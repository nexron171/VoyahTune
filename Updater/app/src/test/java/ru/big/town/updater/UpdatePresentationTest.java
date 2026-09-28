package ru.big.town.updater;
import org.junit.Test;
import static org.junit.Assert.*;
public final class UpdatePresentationTest {
    private UpdatePresentation view(String phase,long done,long count){return UpdatePresentation.from(phase,true,"Установка Native",145,145,done,count);}
    @Test public void installationUsesCompletedStepsNotDownloadBytes(){
        UpdatePresentation p=view("applying",4,8);
        assertEquals(50,p.percent);assertFalse(p.indeterminate);assertTrue(p.busy);assertEquals("",p.command);
        assertEquals("Завершено 4 из 8 шагов",p.progressNote);
        assertFalse(view("applying",8,8).success);
    }
    @Test public void oldOrInvalidStepCountersDoNotInventProgress(){
        assertTrue(view("applying",0,0).indeterminate);
        assertTrue(view("applying",9,8).indeterminate);
        assertTrue(view("applying",-1,8).indeterminate);
    }
    @Test public void onlyPostbootCommitIsSuccess(){
        for(String phase:new String[]{"verifying","preparing","reboot-pending","validating"}){
            UpdatePresentation p=view(phase,8,8);assertTrue(p.meter);assertTrue(p.indeterminate);assertFalse(p.success);assertTrue(p.busy);
        }
        assertTrue(view("committed",8,8).success);
    }
    @Test public void preparationFailureCanRetryButRepairCannot(){
        assertEquals("apply",view("verified",0,8).command);
        assertEquals("close",view("repair-required",5,8).command);
        assertEquals("check",view("failed",0,0).command);
    }
    @Test public void downloadUsesBytesAndHandlesUnknownLength(){
        assertEquals(50,UpdatePresentation.from("downloading",true,"",50,100,8,8).percent);
        assertTrue(UpdatePresentation.from("downloading",true,"",0,0,8,8).indeterminate);
    }
}
