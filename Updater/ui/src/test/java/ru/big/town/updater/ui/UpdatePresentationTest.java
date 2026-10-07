package ru.big.town.updater.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public final class UpdatePresentationTest {
    @Test public void explicitFinishHidesInactiveResultsButKeepsActiveWork(){
        assertEquals("idle",UpdatePresentation.menuPhase("committed",true));
        assertEquals("committed",UpdatePresentation.menuPhase("committed",false));
        for(String phase:new String[]{"verified","repair-required","failed","idle"})
            assertEquals("idle",UpdatePresentation.menuPhase(phase,true));
        for(String phase:new String[]{"checking","downloading","verifying","preparing","applying","reboot-pending","validating"})
            assertEquals(phase,UpdatePresentation.menuPhase(phase,true));
    }
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
        assertEquals("Завершить",view("committed",8,8).primary);
        assertEquals("finish",view("committed",8,8).command);
    }
    @Test public void terminalErrorsAlwaysOfferFinish(){
        assertEquals("apply",view("verified",0,8).command);
        for(String phase:new String[]{"repair-required","failed"}) {
            assertEquals("Завершить",view(phase,5,8).primary);
            assertEquals("finish",view(phase,5,8).command);
        }
    }
    @Test public void errorsOutsideFailurePhasesAlsoOfferFinish(){
        for(String phase:new String[]{"idle","verified","checking","applying"}) {
            UpdatePresentation p=view(phase,1,8);
            boolean busy=p.busy;
            p.offerFinish(true);
            assertEquals("Завершить",p.primary);
            assertEquals("finish",p.command);
            assertFalse(p.secondary);
            assertEquals(busy,p.busy);
        }
    }
    @Test public void downloadUsesBytesAndHandlesUnknownLength(){
        assertEquals(50,UpdatePresentation.from("downloading",true,"",50,100,8,8).percent);
        assertTrue(UpdatePresentation.from("downloading",true,"",0,0,8,8).indeterminate);
    }
}
