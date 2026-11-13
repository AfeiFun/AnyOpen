package dev.fly.hotspotctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int BG      = Color.parseColor("#0B0F14");
    private static final int CARD    = Color.parseColor("#151A21");
    private static final int TEXT    = Color.parseColor("#E6EDF3");
    private static final int DIM     = Color.parseColor("#8B98A5");
    private static final int ACCENT  = Color.parseColor("#3DDC84");
    private static final int DANGER  = Color.parseColor("#F85149");
    private static final int WARN    = Color.parseColor("#F0B429");

    private static final String HS = "/data/adb/hotspot/hs";

    private static final String[][] HELP_SECTIONS = {
            {"状态区",
                    "核心 —— mihomo 进程号。为空表示核心没运行，这时手机和热点都不走代理。\n\n" +
                    "手机流量 —— tun 设备名（Meta）。有值表示手机自己也走这条代理；单核心模式下它和热点客户端用同一套规则、同一个出口。\n\n" +
                    "上游网络 —— 当前真正在用的上网网卡：wlan0 是 Wi-Fi，rmnet_dataX 是蜂窝。切网后自动跟随，不用手动改。\n\n" +
                    "热点 —— 热点网卡名与网段。网段每次开机可能变化，脚本不写死，会自动适配。\n\n" +
                    "透明代理 —— TPROXY 是否已挂到热点口。显示“未挂载”时客户端会直连（能上网但没走代理）。\n\n" +
                    "已转发 —— 累计经过代理的 TCP / UDP 包数。数字在涨就说明真的抓到了流量。"},
            {"按钮",
                    "停止 / 启动代理 —— 启停整条链路（核心 + iptables 规则），不影响热点本身。长按等于重启核心。\n\n" +
                    "测出口 —— 打一次 api.ip.sb 并显示出口 IP。若与手机浏览器里看到的不一致，说明代理没生效。\n\n" +
                    "日志 —— run.log 记守护动作与规则重建；core.log 记每条连接命中哪条规则、走哪个节点。排查先看这里。\n\n" +
                    "热点设置 / 开关热点 —— 前者跳系统热点页；后者由 App 代你点一下（打开设置页→点开关→回来），免去手动操作。\n\n" +
                    "更新配置 —— 从 CMFA 拉取最新订阅，重新生成配置并重启核心（先校验后替换，失败保留旧配置）。\n\n" +
                    "打开 CMFA —— 订阅、节点、规则组都在那里改，改完回来点“更新配置”。\n\n" +
                    "面板 —— 内嵌 mihomo 自带的 MetaCubeXD：看实时连接、切节点、查规则命中、改模式。\n\n" +
                    "重启核心 —— 只重启 mihomo，不动 iptables，通常 2 秒内恢复。\n\n" +
                    "全局 / 规则 / 直连 —— 分流模式。规则＝按订阅里的 rules 走分流；全局＝所有流量强制走代理；直连＝全部不走代理（热点照常）。切换即时生效并写入配置，重启后保持。"},
            {"常见情况",
                    "· 客户端国内能上、国外全卡：多半是节点问题或 UDP 被拦，先看日志里的 dial 报错。\n\n" +
                    "· 客户端完全上不了网：看“透明代理”是否未挂载、核心是否在跑。\n\n" +
                    "· 热点自己关了：系统 10 分钟无客户端会关闭（插着电时不触发）。\n\n" +
                    "· 首次使用需要在 Magisk 里允许一次超级用户权限。\n\n" +
                    "· 换机/换 ROM：热点网卡名可能不是 wlan2，改 run.sh 里 ap() 的列表即可。"}
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView pill;
    private TextView ipValue;
    private Button toggle;
    private Button hotspotBtn;
    private TextView hint;

    private final Map<String, TextView> rows = new LinkedHashMap<>();
    private final Map<String, TextView> modeChips = new LinkedHashMap<>();
    private Map<String, String> state = new LinkedHashMap<>();
    private boolean busy = false;
    private boolean resumed = false;
    private String lastIp = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            probe();
            ui.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        io.execute(() -> {
            sh("su -c id");
            // 某些 ROM 会给新装应用打上 REJECT_ALL 网络策略，导致连 127.0.0.1 都不通
            sh("su -c 'cmd netpolicy add restrict-background-whitelist " + android.os.Process.myUid() + "'");
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
    }

    // ---------------- UI ----------------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sc.addView(root);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("热点代理");
        title.setTextColor(TEXT);
        title.setTextSize(26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headText.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Mi 10S · 单核心 mihomo · 热点客户端零配置");
        sub.setTextColor(DIM);
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, 0);
        headText.addView(sub);

        head.addView(headText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView helpBtn = new TextView(this);
        helpBtn.setText("?");
        helpBtn.setTextSize(17);
        helpBtn.setTextColor(TEXT);
        helpBtn.setGravity(Gravity.CENTER);
        helpBtn.setBackground(round(18, Color.parseColor("#1B222B"), 1));
        helpBtn.setOnClickListener(v -> showHelp());
        head.addView(helpBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.bottomMargin = dp(18);
        root.addView(head, headLp);

        // 状态行：pill + 出口 IP
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        pill = new TextView(this);
        pill.setText("检测中");
        pill.setTextSize(12);
        pill.setTextColor(DIM);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(round(20, Color.parseColor("#1B222B"), 0));
        statusRow.addView(pill);

        ipValue = new TextView(this);
        ipValue.setText("出口 IP —");
        ipValue.setTextColor(DIM);
        ipValue.setTextSize(12);
        ipValue.setPadding(dp(12), 0, 0, 0);
        statusRow.addView(ipValue);

        root.addView(statusRow);

        // 主开关
        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setText("启动代理");
        toggle.setTextSize(16);
        toggle.setTextColor(Color.parseColor("#04140A"));
        toggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        tp.topMargin = dp(18);
        root.addView(toggle, tp);
        toggle.setOnClickListener(v -> onToggle());
        toggle.setOnLongClickListener(v -> { restartCore(); return true; });

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrp.topMargin = dp(10);
        root.addView(modeRow, mrp);
        addModeChip(modeRow, "global", "全局");
        addModeChip(modeRow, "rule", "规则");
        addModeChip(modeRow, "direct", "直连");

        hint = new TextView(this);
        hint.setText("");
        hint.setTextColor(WARN);
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, 0);
        root.addView(hint);

        // 状态卡
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(20, CARD, 1));
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(18);
        root.addView(card, cp);

        addRow(card, "core", "核心", "mihomo 进程号 · 空 = 未运行，此时不走代理");
        addRow(card, "tun", "手机流量", "tun 设备名 · 有值 = 手机自身流量也被接管");
        addRow(card, "up", "上游网络", "正在用的网卡 · wlan0 = Wi-Fi，rmnet = 蜂窝");
        addRow(card, "ap", "热点", "热点网卡与网段 · 每次开机可能变化，脚本自适应");
        addRow(card, "rule", "透明代理", "TPROXY 是否挂到热点口 · 未挂载时客户端直连");
        addRow(card, "traffic", "已转发", "累计抓到的 TCP / UDP 包数 · 在涨说明真通了");

        // 操作
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(16);
        root.addView(actions, ap);

        actions.addView(smallButton("测出口", v -> testIp()), weight());
        actions.addView(smallButton("日志", v -> showLog()), weight());
        actions.addView(smallButton("热点设置", v -> openTether()), weight());

        TextView capA = new TextView(this);
        capA.setText("测出口 = 确认当前真实出口 · 日志 = 看规则命中与报错 · 热点设置 = 系统热点页");
        capA.setTextColor(Color.parseColor("#5A6572"));
        capA.setTextSize(10);
        capA.setPadding(dp(6), dp(6), 0, 0);
        root.addView(capA);

        LinearLayout actions2 = new LinearLayout(this);
        actions2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap2.topMargin = dp(8);
        root.addView(actions2, ap2);
        actions2.addView(smallButton("更新配置", v -> regenConfig()), weight());
        actions2.addView(smallButton("打开 CMFA", v -> openCmfa()), weight());
        actions2.addView(smallButton("重启核心", v -> restartCore()), weight());

        TextView capB = new TextView(this);
        capB.setText("更新配置 = 从 CMFA 拉最新订阅并重启 · 打开 CMFA = 改订阅/节点 · 重启核心 = 只重启 mihomo");
        capB.setTextColor(Color.parseColor("#5A6572"));
        capB.setTextSize(10);
        capB.setPadding(dp(6), dp(6), 0, 0);
        root.addView(capB);

        LinearLayout actions3 = new LinearLayout(this);
        actions3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap3.topMargin = dp(14);
        root.addView(actions3, ap3);

        hotspotBtn = new Button(this);
        hotspotBtn.setAllCaps(false);
        hotspotBtn.setText("开启热点");
        hotspotBtn.setTextSize(15);
        hotspotBtn.setTextColor(ACCENT);
        hotspotBtn.setBackground(round(14, Color.parseColor("#12281B"), 1));
        hotspotBtn.setOnClickListener(v -> toggleHotspot());
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(0, dp(52), 1.6f);
        hbLp.setMargins(dp(4), 0, dp(4), 0);
        actions3.addView(hotspotBtn, hbLp);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        hlp.setMargins(dp(4), 0, dp(4), 0);
        actions3.addView(smallButton("说明", v -> showHelp()), hlp);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        plp.setMargins(dp(4), 0, dp(4), 0);
        actions3.addView(smallButton("面板", v ->
                startActivity(new android.content.Intent(this, PanelActivity.class))), plp);

        TextView foot = new TextView(this);
        foot.setText("长按主按钮 = 重启核心 · 数据每 3 秒刷新 · 右上角 ? 看完整说明");
        foot.setTextColor(Color.parseColor("#5A6572"));
        foot.setTextSize(11);
        foot.setPadding(0, dp(20), 0, 0);
        root.addView(foot);

        return sc;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private View smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(TEXT);
        b.setBackground(round(14, Color.parseColor("#1B222B"), 1));
        b.setOnClickListener(l);
        return b;
    }

    private void addRow(LinearLayout parent, String key, String label, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(13);
        line.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText("—");
        v.setTextColor(TEXT);
        v.setTextSize(13);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.END);
        line.addView(v);
        row.addView(line);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextColor(Color.parseColor("#5A6572"));
        h.setTextSize(11);
        h.setPadding(0, dp(3), 0, 0);
        row.addView(h);

        rows.put(key, v);
        parent.addView(row);
    }

    private void addModeChip(LinearLayout parent, String key, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(9), 0, dp(9));
        t.setTextColor(DIM);
        t.setBackground(round(12, CARD, 1));
        t.setOnClickListener(v -> setMode(key));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(t, p);
        modeChips.put(key, t);
    }

    private GradientDrawable round(int radiusDp, int fill, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp));
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(1), Color.parseColor("#232A33"));
        return g;
    }

    // ---------------- 动作 ----------------

    private void onToggle() {
        if (busy) return;
        boolean running = !text("core").isEmpty();
        run(running ? HS + " off" : HS + " on", running ? "已停止代理" : "已启动代理");
    }

    private void restartCore() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("重启核心？")
                .setMessage("会短暂断开手机与热点的代理。")
                .setPositiveButton("重启", (d, w) -> run(HS + " restart", "正在重启"))
                .setNegativeButton("取消", null)
                .show();
    }

    private void testIp() {
        if (busy) return;
        busy = true;
        pill.setText("测试中");
        io.execute(() -> {
            String ip = sh("su -c 'curl -s -m 10 https://api.ip.sb/ip'").trim().replace("\n", "");
            ui.post(() -> {
                busy = false;
                if (ip.startsWith("ERR") || ip.isEmpty()) {
                    ipValue.setText("出口 IP 获取失败");
                    ipValue.setTextColor(DANGER);
                } else {
                    lastIp = ip;
                    ipValue.setText("出口 " + ip);
                    ipValue.setTextColor(ACCENT);
                }
                probe();
            });
        });
    }

    private void setMode(String key) {
        if (busy) return;
        String label = key.equals("global") ? "全局" : key.equals("direct") ? "直连" : "规则";
        run(HS + " mode " + key, "已切换到「" + label + "」");
    }

    private void regenConfig() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("从 CMFA 拉取最新订阅？")
                .setMessage("会重新生成配置并重启核心，热点客户端会短暂断开。")
                .setPositiveButton("更新", (d, w) -> run(HS + " regen", "正在更新配置"))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openCmfa() {
        io.execute(() -> sh("su -c 'monkey -p com.github.metacubex.clash.meta -c android.intent.category.LAUNCHER 1'"));
    }

    private void showHelp() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));

        final Map<TextView, TextView> pairs = new LinkedHashMap<>();
        for (int i = 0; i < HELP_SECTIONS.length; i++) {
            final String name = HELP_SECTIONS[i][0];
            final String bodyText = HELP_SECTIONS[i][1];

            final TextView head = new TextView(this);
            head.setText((i == 0 ? "▾  " : "▸  ") + name);
            head.setTextSize(14);
            head.setTextColor(ACCENT);
            head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.setPadding(dp(14), dp(14), dp(14), dp(14));
            head.setBackground(round(12, Color.parseColor("#1B222B"), 0));

            final TextView body = new TextView(this);
            body.setText(bodyText);
            body.setTextSize(13);
            body.setTextColor(TEXT);
            body.setLineSpacing(dp(3), 1f);
            body.setPadding(dp(14), dp(10), dp(14), dp(14));
            body.setVisibility(i == 0 ? View.VISIBLE : View.GONE);

            head.setOnClickListener(v -> {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                head.setText((show ? "▾  " : "▸  ") + name);
            });

            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = dp(8);
            box.addView(head, hp);
            box.addView(body);
            pairs.put(head, body);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("功能说明")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    private int[] screenSize() {
        String out = sh("su -c 'wm size'");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(out);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return new int[]{1080, 2340};
    }

    /** root 下代点系统热点开关：打开设置页 → 点开关 → 回本页，并校验是否真的切换成功 */
    private void toggleHotspot() {
        if (busy) return;
        busy = true;
        final boolean wasOn = !state.getOrDefault("iface", "").isEmpty();
        hint.setText(wasOn ? "正在关闭热点…" : "正在开启热点…");
        io.execute(() -> {
            int[] wh = screenSize();
            sh("su -c 'am start -a android.settings.TETHER_SETTINGS'");
            sleep(1500);
            sh("su -c 'input tap " + (int) (wh[0] * 0.852) + " " + (int) (wh[1] * 0.263) + "'");
            sleep(2600);
            String after = sh("su -c 'sh /data/adb/hotspot/hs probe'");
            boolean nowOn = after.contains("iface=wlan") || after.contains("iface=ap");
            sh("su -c 'am start -n dev.fly.hotspotctl/.MainActivity'");
            ui.post(() -> {
                busy = false;
                if (nowOn == wasOn) {
                    hint.setText("没点中开关，请用「热点设置」手动切换");
                } else {
                    hint.setText("");
                    Toast.makeText(this, nowOn ? "热点已开启" : "热点已关闭", Toast.LENGTH_SHORT).show();
                }
                probe();
            });
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void openTether() {
        io.execute(() -> sh("su -c 'am start -a android.settings.TETHER_SETTINGS'"));
    }

    private void showLog() {
        io.execute(() -> {
            String run = sh("su -c 'tail -n 60 /data/adb/hotspot/log/run.log'");
            String core = sh("su -c 'tail -n 40 /data/adb/hotspot/log/core.log'");
            String text = "— run.log —\n" + run + "\n— core.log —\n" + core;
            ui.post(() -> {
                TextView tv = new TextView(this);
                tv.setText(text);
                tv.setTextSize(11);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(TEXT);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(16), dp(16), dp(16), dp(16));
                tv.setBackgroundColor(Color.parseColor("#0F1318"));
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this)
                        .setTitle("运行日志")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        });
    }

    private void run(String cmd, String msg) {
        busy = true;
        hint.setText(msg + "…");
        io.execute(() -> {
            String out = sh("su -c '" + cmd + "'");
            ui.post(() -> {
                busy = false;
                hint.setText(out.startsWith("ERR") ? "执行失败：" + out : "");
                probe();
            });
        });
    }

    // ---------------- 状态轮询 ----------------

    private void probe() {
        if (busy) return;
        io.execute(() -> {
            String raw = sh("su -c '" + HS + " probe'");
            if (raw.startsWith("ERR") || raw.trim().isEmpty()) {
                ui.post(() -> renderFailure(raw));
                return;
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : raw.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            ui.post(() -> render(m));
        });
    }

    private void renderFailure(String raw) {
        pill.setText("无 root 或未安装");
        pill.setTextColor(DANGER);
        pill.setBackground(round(20, Color.parseColor("#2A1416"), 0));
        toggle.setText("重新检测");
        hint.setText("需要 Magisk 授权：" + trim(raw));
        for (TextView v : rows.values()) v.setText("—");
    }

    private void render(Map<String, String> m) {
        state = m;
        boolean coreUp = !m.getOrDefault("core", "").isEmpty();
        boolean tunUp = !m.getOrDefault("tun", "").isEmpty();
        boolean hooked = "1".equals(m.getOrDefault("hook", "0"))
                && !m.getOrDefault("iface", "").isEmpty();

        if (coreUp) {
            pill.setText(hooked ? "运行中 · 热点已接管" : "核心运行 · 热点未开");
            pill.setTextColor(ACCENT);
            pill.setBackground(round(20, Color.parseColor("#12281B"), 0));
            toggle.setText("停止代理");
            toggle.setTextColor(Color.parseColor("#04140A"));
            toggle.setBackground(round(16, ACCENT, 0));
        } else {
            pill.setText("已停止");
            pill.setTextColor(DIM);
            pill.setBackground(round(20, Color.parseColor("#1B222B"), 0));
            toggle.setText("启动代理");
            toggle.setTextColor(Color.parseColor("#04140A"));
            toggle.setBackground(round(16, ACCENT, 0));
        }

        rows.get("core").setText(coreUp ? "mihomo " + m.get("core") : "未运行");
        rows.get("core").setTextColor(coreUp ? TEXT : DIM);

        rows.get("tun").setText(tunUp ? m.get("tun") : "未接管");
        rows.get("tun").setTextColor(tunUp ? TEXT : DIM);

        String up = m.getOrDefault("up", "");
        rows.get("up").setText(up.isEmpty() ? "—" : up);
        rows.get("up").setTextColor(TEXT);

        String iface = m.getOrDefault("iface", "");
        String ip = m.getOrDefault("iface_ip", "");
        rows.get("ap").setText(iface.isEmpty() ? "未开启" : iface + "  " + ip);
        rows.get("ap").setTextColor(iface.isEmpty() ? DIM : TEXT);

        rows.get("rule").setText(hooked ? "TPROXY 已挂" : "未挂载");
        rows.get("rule").setTextColor(hooked ? ACCENT : WARN);

        String tcp = m.getOrDefault("tcp", "");
        String udp = m.getOrDefault("udp", "");
        if (tcp.isEmpty()) tcp = "0";
        if (udp.isEmpty()) udp = "0";
        rows.get("traffic").setText(tcp + " TCP / " + udp + " UDP");
        rows.get("traffic").setTextColor(TEXT);

        String curMode = m.getOrDefault("mode", "rule");
        for (Map.Entry<String, TextView> e : modeChips.entrySet()) {
            boolean on = e.getKey().equals(curMode);
            e.getValue().setTextColor(on ? Color.parseColor("#04140A") : DIM);
            e.getValue().setBackground(round(12, on ? ACCENT : CARD, on ? 0 : 1));
        }

        if (hotspotBtn != null) {
            hotspotBtn.setText(iface.isEmpty() ? "开启热点" : "关闭热点");
        }

        if (!lastIp.isEmpty()) {
            ipValue.setText("出口 " + lastIp);
            ipValue.setTextColor(ACCENT);
        } else {
            ipValue.setText("出口 IP —");
            ipValue.setTextColor(DIM);
        }
    }

    private String text(String key) {
        String v = state.get(key);
        return v == null ? "" : v;
    }

    private String trim(String s) {
        s = s.replace("ERR:", "").trim();
        return s.length() > 90 ? s.substring(0, 90) : s;
    }

    // ---------------- shell ----------------

    private String sh(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
        return sb.toString();
    }
}
