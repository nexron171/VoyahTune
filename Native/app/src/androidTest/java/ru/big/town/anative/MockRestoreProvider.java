package ru.big.town.anative;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;

/** Mock of RestoreMode's persisted selection, accessed through real ContentResolver IPC. */
public final class MockRestoreProvider extends ContentProvider {
    private String selected="COMFORT";
    private int reads,writes,caller;
    @Override public boolean onCreate(){return true;}
    @Override public synchronized Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){
        reads++;caller=Binder.getCallingUid();
        MatrixCursor result=new MatrixCursor(new String[]{"configuredDriveMode","suspensionDriveOverride","lastMediumDrive","currentTripDrive"});
        result.addRow(new Object[]{"COMFORT",selected,"COMFORT",selected});return result;
    }
    @Override public synchronized int update(Uri u,ContentValues v,String selection,String[] args){
        caller=Binder.getCallingUid();if(!"widget".equals(v.getAsString("driveSelectionSource")))throw new IllegalArgumentException("source");
        String mode=v.getAsString("driveSelectionMode");if(!java.util.Arrays.asList("ECO","COMFORT","SPORT","OUTING","SNOW","INDIVIDUAL").contains(mode))return 0;
        writes++;selected=mode;return 1;
    }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras){
        if("reset".equals(method)){selected="COMFORT";reads=writes=0;}
        Bundle b=new Bundle();b.putInt("reads",reads);b.putInt("writes",writes);b.putInt("caller",caller);b.putInt("providerUid",android.os.Process.myUid());b.putString("selected",selected);return b;
    }
    @Override public String getType(Uri u){return "vnd.android.cursor.item/drive";}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
