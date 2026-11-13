package io.github.afeifun.anyopen;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 内嵌 mihomo 自带的 MetaCubeXD 面板（由内核在 9091 上直接托管） */
public class PanelActivity extends Activity {

    private static final String URL = "http://127.0.0.1:9091/ui/";
    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F14"));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(14), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("面板");
        title.setTextColor(Color.parseColor("#E6EDF3"));
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button reload = new Button(this);
        reload.setAllCaps(false);
        reload.setText("刷新");
        reload.setTextSize(13);
        reload.setTextColor(Color.parseColor("#3DDC84"));
        reload.setOnClickListener(v -> web.reload());
        bar.addView(reload);

        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("返回");
        close.setTextSize(13);
        close.setTextColor(Color.parseColor("#E6EDF3"));
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setBackgroundColor(Color.parseColor("#0B0F14"));
        web.setWebViewClient(new WebViewClient());
        web.loadUrl(URL);

        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
