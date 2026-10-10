// Throwaway spike: can a normal (non-shell) app launch other apps on the cover display?
package dev.opencover.spike;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class Main extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setTextSize(20);
        setContentView(tv);
        String pkg = getIntent().getStringExtra("pkg");
        int display = getIntent().getIntExtra("display", 1);
        if (pkg == null) { tv.setText("spike: pass --es pkg <package>"); return; }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(display);
            startActivity(i, o.toBundle());
            Log.i("OCSPIKE", "OK launched " + pkg + " on display " + display);
            tv.setText("launched " + pkg);
        } catch (Exception e) {
            Log.e("OCSPIKE", "FAIL " + pkg, e);
            tv.setText("FAIL: " + e);
        }
    }
}
