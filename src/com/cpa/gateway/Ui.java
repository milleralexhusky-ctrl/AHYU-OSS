package com.cpa.gateway;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One small design system so every screen looks like it belongs to the same app. */
final class Ui {

    /* ----------------------------------------------------------- 层结构规范
     *
     * 全 App 只允许两种"面"，不要混用：
     *   · 导航层（标题栏、底部栏、按钮、控件）= 玻璃，背景要透出来      → NAV_* / GlassDrawable.navBar
     *   · 内容层（卡片、列表行、文本块、输入框）= 实体面，背景不许透过来  → SURFACE*
     *
     * 背景按配色现算（Swatch）；玻璃只负责自己那一块，不做整屏模糊。
     * 这条线是给"液体玻璃"留的地基：以后给导航层加折射，内容层不受影响。
     */

    /* ---------------------------------------------------------- 不透明度尺度
     *
     * 改之前：全工程散落 21 个 alpha 值 ——
     *     0x14 0x1A 0x1F 0x22 0x24 0x26 0x2A 0x2E 0x33 0x3C 0x3D 0x46 …
     * 其中 0x22/0x24/0x26（13.3%/14.1%/14.9%）差不到 2%，
     * 0x3C/0x3D（23.5%/23.9%）差 0.4% —— 人眼完全分辨不出，
     * 但它们是十几个独立的数字，改一处不影响另一处，整体就没有节奏。
     *
     * 为什么用**几何级数**而不是"感知均匀"：
     * 我一开始按 gamma 2.2 做了感知等距分档，结果所有档都挤到 40% 以上
     * （0.413/0.566/0.680/…），而实际用量 90% 都在 8%~30%。
     * 原因是半透明叠加本身就是**乘法**的：
     *     C = a + C_prev·(1-a)      →  1-C = (1-a)(1-C_prev)
     * 所以 alpha 该按 (1-a) 的几何级数取，不是按 a 的感知值等距取。
     *
     * 取底 r = (1-0.235)^(1/4) ≈ 0.935，得到：
     *     A1 6.5%  A2 12.5%  A3 18.2%  A4 23.5%  A5 28.5%  A6 33.1%
     * 相邻两档的"两层叠加结果"差 0.10~0.11，正好在可辨范围内，
     * 而六个档就覆盖了原来十一个档的区间。
     */
    static int ALPHA_1 = 17;    /*  6.5%  极淡：分隔线、极弱高光 */
    static int ALPHA_2 = 32;    /* 12.5%  淡：标签底、次级描边 */
    static int ALPHA_3 = 46;    /* 18.2%  中：导航玻璃、卡片描边 */
    static int ALPHA_4 = 60;    /* 23.5%  明显：强调描边、按压层 */
    static int ALPHA_5 = 73;    /* 28.5%  强：深色面罩 */
    static int ALPHA_6 = 84;    /* 33.1%  很强：模态遮罩 */

    /**
     * 按压涟漪色。
     *
     * 原来全工程写了 8 次 `Color.parseColor("#33FFFFFF")`：
     * 每次按压事件都要解析一遍十六进制字符串。改成常量后，
     * 这 8 处变成同一个 int 直接引用 —— 顺便少 8 次字符串解析。
     */
    static final int RIPPLE = 0x33FFFFFF;

    /** 白色、按尺度取不透明度。 */
    static int whiteA(int a) {
        return (a << 24) | 0x00FFFFFF;
    }

    /** 黑色、按尺度取不透明度。 */
    static int blackA(int a) {
        return a << 24;
    }
    /* 内容层：基本实心的深橄榄。白字压在它上面永远清楚，画卷只当作环境色。 */
    /** 浅色模式开关：applyTheme 里按它给两套值。 */
    static boolean LIGHT;

    /* 下面这些配色从"编译期常量"改成可变字段：浅色/深色要换整套值 */
    static int SURFACE = Color.parseColor("#EC1A2113");
    static int SURFACE_2 = Color.parseColor("#F5222A19");
    static int SURFACE_STROKE = whiteA(ALPHA_2);

    /* 导航层：真正透的玻璃，画透过来。 */
    static int NAV_FILL = whiteA(ALPHA_3);
    static int NAV_STROKE = whiteA(ALPHA_4);

    // 旧名字保留，现在指向"内容面"，这样内容卡片会统一变成实体
    static int GLASS = SURFACE;
    static int GLASS_STRONG = SURFACE_2;
    static int GLASS_STROKE = SURFACE_STROKE;
    static int GLASS_HI = whiteA(ALPHA_1);
    static int BG = Color.parseColor("#070A11");
    static int CARD = SURFACE;
    /** 能力标签的底色：比卡片再淡一档，避免标签比正文还抢眼。 */
    static int TAG_BG = whiteA(ALPHA_2);
    static int CARD_2 = SURFACE_2;
    static int STROKE = SURFACE_STROKE;



    /** 深色一套（原值）／浅色一套。切模式后靠 applyTheme 重设。 */
    private static void applyPalette(boolean light) {
        if (light) {
            // 浅色这套的关键：卡片**不能是实心白**。
            // 原来 #F2FFFFFF 压在提亮过的照片上，卡片和背景几乎同色，
            // 描边 0x1F000000、阴影 0x14000000 又都极淡 —— 所以看起来"白茫茫一片、没有层次"。
            // 现在改成半透明白，让底图透出来，卡片靠阴影和圆角浮起来。
            SURFACE = Color.parseColor("#D2FFFFFF");      // 82% 白：压掉刺眼高光，底图仍透得出来
            SURFACE_2 = Color.parseColor("#E4FFFFFF");    // 需要更实的面（对话框、导航）
            SURFACE_STROKE = blackA(ALPHA_2);            /* 12.5% 黑：描边加深，卡片边界看得见 */
            NAV_FILL = whiteA(ALPHA_6);                  /* 33% 白：浅底上导航层要足够实 */
            NAV_STROKE = blackA(ALPHA_2);
            GLASS_HI = whiteA(ALPHA_3);                  /* 浅色下高光要更明显才看得出玻璃感 */
            BG = Color.parseColor("#DDE0D2");             // 比卡片深一档：留出层次，又不至于满屏发白
            TEXT = Color.parseColor("#14161A");           // 正文近黑，浅底上更利落
            MUTED = Color.parseColor("#6A7062");          // 暖灰，不再是偏冷的 #5F6368
            CHIP = Color.parseColor("#E7E9E0");
            GREEN_SOFT = mix(GREEN, 0xFFFFFFFF, 0.86f);
            RED_SOFT = mix(RED, 0xFFFFFFFF, 0.88f);
            // 强调文字（一键做任务、复制、添加账号…）在浅底上要压深，
            // 否则粉红/浅绿配白玻璃看着又淡又脏。
            GREEN = mix(GREEN, 0xFF000000, 0.28f);
            RED = mix(RED, 0xFF000000, 0.32f);
            // 浅底上的主题柔色：主题色混白，每套主题各有自己的浅色，而不是一律压黑
            ACCENT_SOFT = mix(ACCENT, 0xFFFFFFFF, 0.87f);
            // 小标签文字：浅色下原来的 #C3D2A4 这种浅色在白底上看不清，改用加深的主题色
            ACCENT_LIGHT = mix(ACCENT, 0xFF000000, 0.35f);
        } else {
            SURFACE = Color.parseColor("#EC1A2113");
            SURFACE_2 = Color.parseColor("#F5222A19");
            SURFACE_STROKE = whiteA(ALPHA_2);
            NAV_FILL = whiteA(ALPHA_3);
            NAV_STROKE = whiteA(ALPHA_4);
            GLASS_HI = whiteA(ALPHA_1);
            BG = Color.parseColor("#070A11");
            TEXT = Color.parseColor("#FFFFFF");
            MUTED = Color.parseColor("#CBD0BE");
            CHIP = Color.parseColor("#232A1C");
            GREEN_SOFT = Color.parseColor("#1E2A18");
            RED_SOFT = Color.parseColor("#33211B");
            GREEN = Color.parseColor("#86B36A");
            RED = Color.parseColor("#CE7A63");
        }
        GLASS = SURFACE;
        GLASS_STRONG = SURFACE_2;
        GLASS_STROKE = SURFACE_STROKE;
        CARD = SURFACE;
        CARD_2 = SURFACE_2;
        STROKE = SURFACE_STROKE;
    }
    /** 两色混合：t=0 全是 a，t=1 全是 b。用来把主题色调成浅色下的柔色。 */
    static int mix(int a, int b, float t) {
        float u = 1f - t;
        int r = (int) (android.graphics.Color.red(a) * u + android.graphics.Color.red(b) * t);
        int g = (int) (android.graphics.Color.green(a) * u + android.graphics.Color.green(b) * t);
        int bl = (int) (android.graphics.Color.blue(a) * u + android.graphics.Color.blue(b) * t);
        return android.graphics.Color.rgb(Math.min(255, Math.max(0, r)),
                Math.min(255, Math.max(0, g)),
                Math.min(255, Math.max(0, bl)));
    }

    /** Accent colours are themeable; see applyTheme(). */
    static int ACCENT = Color.parseColor("#93A868");
    static int ACCENT_2 = Color.parseColor("#C9A227");
    static int ACCENT_SOFT = Color.parseColor("#2A3120");
    static int ACCENT_LIGHT = Color.parseColor("#C3D2A4");

    /** Palette choices offered in Settings. key -> {accent, accent2, light, soft}. */
    static final String[][] THEMES = {
            {"spring", "绿金", "#93A868", "#C9A227", "#C3D2A4", "#2A3120"},
            {"blue", "蓝紫", "#5B8CFF", "#A855F7", "#8FB3FF", "#1E2C45"},
            {"teal", "青绿", "#14C8B0", "#3ED6A0", "#6FE7D2", "#123A38"},
            {"amber", "暖橙", "#FF9F45", "#FF6B6B", "#FFC078", "#3A2A1A"},
            {"rose", "玫红", "#FF5C8A", "#A855F7", "#FF9BB8", "#3A1A2A"},
            {"indigo", "靛蓝", "#4F6BFF", "#2BB8FF", "#8AA4FF", "#1B2340"},
            {"graphite", "石墨", "#9AA5B4", "#6B7684", "#C3CCD8", "#242A33"}};

    static String themeLabel(String key) {
        for (String[] t : THEMES) if (t[0].equals(key)) return t[1];
        return THEMES[0][1];
    }

    static int themeColor(String key, int index) {
        for (String[] t : THEMES) {
            if (t[0].equals(key)) return Color.parseColor(t[index]);
        }
        return Color.parseColor(THEMES[0][index]);
    }

    static void applyTheme(String key) {
        boolean light = false;
        try {
            light = com.cpa.gateway.core.Gateway.config().lightMode;
        } catch (Throwable ignored) {
        }
        applyTheme(key, light);
    }

    /**
     * 显式指定深浅模式。
     *
     * 进程最早（CpaApp.attachBaseContext）就要用它：底纹的蒙版与缓存键都跟模式有关，
     * 而那时 Gateway 的配置还没初始化，读不到 lightMode——只能由调用方把值传进来。
     */
    static void applyTheme(String key, boolean lightMode) {
        String k = key == null || key.isEmpty() ? "spring" : key;
        ACCENT = themeColor(k, 2);
        ACCENT_2 = themeColor(k, 3);
        ACCENT_LIGHT = themeColor(k, 4);
        ACCENT_SOFT = themeColor(k, 5);
        boolean light = lightMode;
        boolean modeChanged = light != LIGHT || lastPaletteKey == null || !lastPaletteKey.equals(k);
        LIGHT = light;
        lastPaletteKey = k;
        applyPalette(light);
        GlassDrawable.applyMode(light);
        // 模式变了才重建底纹：底纹是"画 + 一层蒙版"，浅色模式下蒙版是提亮而不是压暗
        if (modeChanged) BackdropTex.invalidate();
        // 切换深浅后，系统状态栏/导航栏的图标颜色要立刻跟上，不必等下一个页面重建
        if (modeChanged && lastBarsActivity != null) {
            Activity shown = lastBarsActivity.get();
            if (shown != null) {
                try {
                    paintSystemBars(shown);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 最近一次调用过 paintSystemBars 的页面，用于切换深浅后即时刷新。 */
    private static java.lang.ref.WeakReference<Activity> lastBarsActivity;

    /** 系统栏底色与图标明暗。浅色底要"深色图标"，深色底要"浅色图标"。 */
    static void paintSystemBars(Activity a) {
        if (a == null) return;
        lastBarsActivity = new java.lang.ref.WeakReference<>(a);
        try {
            a.getWindow().setStatusBarColor(BG);
            a.getWindow().setNavigationBarColor(BG);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController ic = a.getWindow().getInsetsController();
                if (ic != null) {
                    int flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(LIGHT ? flags : 0, flags);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 上一次应用的配色/模式，用来判断要不要重建底纹。 */
    private static String lastPaletteKey;
    /** 浅色下这套绿/红在白底上偏灰浊，applyPalette 会换成加深版。 */
    static int GREEN = Color.parseColor("#86B36A");
    static int GREEN_SOFT = Color.parseColor("#1E2A18");
    static int RED = Color.parseColor("#CE7A63");
    static int RED_SOFT = Color.parseColor("#33211B");
    static int TEXT = Color.parseColor("#FFFFFF");
    static int MUTED = Color.parseColor("#CBD0BE");
    static int CHIP = Color.parseColor("#232A1C");

    private Ui() {}

    /**
     * Wraps content in a glassmorphism scaffold: coloured blobs behind, blurred with RenderEffect
     * where the platform supports it, with the content laid on top.
     */
    static android.widget.FrameLayout glassScaffold(android.app.Activity a, View content) {
        return glassScaffold(a, content, 1f);
    }

    /**
     * Same scaffold, with an explicit presence (0..1) for the backdrop layer.
     *
     * <p>This is the "denser the content, the further the visuals recede" rule
     * made concrete: main screen 1.0, list pages 0.65, settings 0.32,
     * error banners 0.15 -- one backdrop, not one per page.
     */
    static android.widget.FrameLayout glassScaffold(android.app.Activity a, View content, float strength) {
        android.widget.FrameLayout container = new android.widget.FrameLayout(a);
        DynamicBackdrop backdrop = new DynamicBackdrop(a);
        backdrop.strength(strength);
        // purely visual: never consumes touch, so taps land on the buttons
        backdrop.setClickable(false);
        backdrop.setFocusable(false);
        container.addView(backdrop, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // deliberately NOT blurred: the artwork must stay perfectly sharp
        final int fallback = statusBarHeight(a);
        final int baseTop = content.getPaddingTop();
        content.setPadding(content.getPaddingLeft(), baseTop + fallback,
                content.getPaddingRight(), content.getPaddingBottom());
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = fallback;
            try {
                top = Math.max(insets.getInsets(android.view.WindowInsets.Type.statusBars()).top, fallback);
            } catch (Throwable ignored) {
            }
            content.setPadding(content.getPaddingLeft(), baseTop + top,
                    content.getPaddingRight(), content.getPaddingBottom());
            return insets;
        });
        container.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return container;
    }

    /** Brand title: a gradient fill plus a soft glow, instead of flat white text. */
    static class GradientTitle extends android.widget.TextView {
        GradientTitle(Context c) {
            super(c);
            setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 31f);
            setLetterSpacing(0.045f);
            setIncludeFontPadding(false);
            setShadowLayer(14f, 0f, 5f, Color.argb(110, 147, 168, 104));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            int[] colors;
            if (LIGHT) {
                // 浅色底上的标题原来是"白 →浅绿"的渐变，白色那段压在照片上等于没有
                // （截图里"CLI"几乎看不见）。浅色下换成"深墨 →主题色"，阴影也去掉：
                // 浅底上加发光阴影只会让字发虚。
                setShadowLayer(0f, 0f, 0f, 0);
                colors = new int[]{0xFF23271E, mix(ACCENT, 0xFF000000, 0.10f), mix(ACCENT, 0xFF000000, 0.40f)};
            } else {
                setShadowLayer(14f, 0f, 5f, Color.argb(110, 147, 168, 104));
                colors = new int[]{Color.parseColor("#FFFFFF"), Color.parseColor("#F0F3E4"),
                        Color.parseColor("#C3D2A4")};
            }
            getPaint().setShader(w > 0 ? new android.graphics.LinearGradient(
                    0, 0, w, 0, colors,
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP) : null);
        }
    }

    static TextView artisticTitle(Context c, String text) {
        GradientTitle t = new GradientTitle(c);
        t.setText(text);
        return t;
    }

    /** Scroll container that never grows past a cap, so a dialog footer stays fully visible. */
    static class MaxHeightScrollView extends android.widget.ScrollView {
        private int maxHeight;

        MaxHeightScrollView(Context c) {
            super(c);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setVerticalScrollBarEnabled(false);
        }

        void setMaxHeight(int h) {
            maxHeight = h;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (maxHeight > 0) {
                heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** Status bar height, so screens never bleed into the notification bar. */
    static int statusBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return dp(c, 24);
    }

    static android.graphics.drawable.Drawable drawablePanel(Context c) {
        return GlassDrawable.panel(dp(c, 28));
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /* ------------------------------------------------------- 模数系统 (modular scale) */

    /*
     * 为什么要引入这个：改这个文件之前，间距是 1/3/4/5/6/7/8/9/10/11/12/13/14/15/16/18/20/24/28
     * 十九个**互不相关**的数字，字号是 11/11.5/12/12.5/13/13.5/14/14.5/15/15.5/16/17/18/20/22
     * 十八个。这些数字本身每个都"说得过去"，问题在于它们之间没有可推导的关系 ——
     * 所以整体观感是"哪一块都对，合起来不协调"。
     *
     * 更具体地说：15 和 15.5 差 0.5sp、12.5 和 13 差 0.5sp，人眼分辨不出来，
     * 但渲染出来就是两套不同的字重观感；13 和 14 差 1dp 同理。
     * 一个尺度里出现"人眼不可分辨但机器不同"的两档，只会制造不一致而拿不到任何好处。
     *
     * 这里给两个尺度：
     *   间距/圆角：4dp 基准，2 倍进阶 —— 4 / 8 / 12 / 16 / 24 / 32 / 48
     *   字号：1.125 比率（相邻档差 12.5%，是"能看出差别"的公认下限附近）
     *        11 / 12 / 13.5 / 15 / 17 / 19
     *
     * 注意：本轮**只替换了取整后像素与原来完全相同、且语义就是间距/圆角/辅助字号的常量**，
     * 所有标题字号和结构性数字（999 全圆、0.6dp 分隔线、48dp 触控下限）原样不动。
     * 这样做的好处是这一轮不可能改变任何布局结果，先建立机制，再逐屏调档次。
     */

    /** 间距基元 4dp：所有 padding / margin / gap 都应是它的倍数。 */
    static final int U = 4;

    /** 间距档：与 {@link #U} 成倍数关系，不再出现 13/14 这种"差 1"的邻居。 */
    static int space(Context c, int n) {
        return dp(c, U * n);
    }

    /*
     * 字号阶（1.125 比率）。
     *
     * 现状是 11/11.5/12/12.5/13/13.5/14/14.5/15/15.5/16/17/18/20/22/26/28/31 —— 十八档，
     * 其中 11.5 和 12 差 0.5sp、13.5 和 14 差 0.5sp，人眼分辨不出来，只会觉得正文
     * "一会儿紧一会儿松"。按 1.125 收成六档，相邻档差 12.5%，刚好在可辨阈值附近：
     *
     *     11  → 12  → 13.5  → 15  → 17  → 19
     *     注脚   辅助    正文     按钮   小标题  标题
     *
     * 标题级（26/28/31）保持原样：它们是"展示字号"，本来就不跟正文同一套节奏，
     * 硬套比率反而会让大标题缩水。
     */
    static final float T_CAPTION = 11f;   // 能力标签、注脚
    static final float T_SMALL   = 12f;   // 次要信息
    static final float T_BODY    = 13.5f; // 正文
    static final float T_BUTTON  = 15f;   // 按钮文字
    static final float T_SUBHEAD = 17f;   // 卡片小标题
    static final float T_HEAD    = 19f;   // 段落标题

    /** 间距档的档位名（读代码时比 sp(c, 4) 直观）。 */
    static final int S1 = 1;   //  4dp  最小间隙
    static final int S2 = 2;   //  8dp  紧凑
    static final int S3 = 3;   // 12dp  常规
    static final int S4 = 4;   // 16dp  卡片内边距
    static final int S6 = 6;   // 24dp  卡片间距
    static final int S8 = 8;   // 32dp  段落间距
    static final int S12 = 12; // 48dp  触控下限

    static GradientDrawable round(Context c, int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    static GradientDrawable roundStroke(Context c, int color, float radius, int strokeColor) {
        GradientDrawable d = round(c, color, radius);
        d.setStroke(dp(c, 1), strokeColor);
        return d;
    }

    /**
     * 复制文本到剪贴板，并在 60 秒后自动清除。
     *
     * 为什么：密钥/地址这类东西留在剪贴板里，会跟着剪贴板历史（以及某些输入法的
     * 云同步）跑到用户看不见的地方。这里不做"立即清"——那会让"复制了过会儿再粘贴"
     * 失败；改成延时清除，留够粘贴时间，又不会一直挂着。
     *
     * 只清**自己写进去的那一份**：拿 label 比对（我们用 "cpa"），
     * 用户中途复制了别的内容就绝不碰（否则会把用户自己的东西清掉，那更糟）。
     */
    static void copyWithAutoClear(final Context c, final String text) {
        try {
            final android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText(CLIP_LABEL, text));
        } catch (Throwable ignored) {
            return;
        }
        try {
            com.cpa.gateway.core.Threads.BG.schedule(() -> {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null || !cm.hasPrimaryClip()) return;
                    android.content.ClipData cur = cm.getPrimaryClip();
                    if (cur == null) return;
                    CharSequence label = cur.getDescription() == null ? null : cur.getDescription().getLabel();
                    if (label == null || !CLIP_LABEL.contentEquals(label)) {
                        return;   // 已经不是我们写的那份了，别动
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        cm.clearPrimaryClip();
                    } else {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
                    }
                    com.cpa.gateway.core.Log.i("剪贴板已按 60 秒时效自动清除（仅清本应用写入的内容）");
                } catch (Throwable ignored) {
                }
            }, 60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    /** 本应用写入剪贴板时用的 label，用于"只清自己那份"的比对。 */
    private static final String CLIP_LABEL = "cpa";

    /** Paints the system bars to match the dark content. */
    /**
     * 让这个页面不进"最近任务"缩略图、也不允许截屏。
     *
     * 登录页会显示账号/验证码，设置页会显示访问 Key——这些不该留在系统的任务快照里。
     */
    /**
     * 给对话框加"禁止截屏 / 不进最近任务缩略图"。
     *
     * 只应由**确实含凭据**的对话框调用：填 token 的输入框、编辑账号的编辑器。
     * 其余对话框（进度、结果、确认）一律不加——它们没有敏感信息，加了只会
     * 挡住用户截图反馈问题。
     */
    static void secureDialog(android.app.Dialog d) {
        if (d == null) return;
        try {
            d.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) {
        }
    }

    static void secure(Activity a) {
        try {
            a.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) {
        }
    }

    static void darkSystemBars(Activity a) {
        try {
            applyTheme(com.cpa.gateway.core.Gateway.config().accentTheme);
        } catch (Throwable ignored) {
        }

        // 每个页面都在 onCreate 最开头走这里：顺手把主题底纹备好（内存 → 磁盘 → 现画）。
        // 这样第一帧画出来就是那张画，而不是"照片还没就绪"的兜底色斑。
        BackdropTex.warmSync(a);
        paintSystemBars(a);
    }

    /* --------------------------------------------------------------- layout */

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static GradientDrawable gradient(int from, int to, float radius, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    /** Frosted card: low-alpha white fill, hairline highlight border, generous radius. */
    static LinearLayout card(Context c, View... children) {
        LinearLayout box = column(c);
        box.setBackground(GlassDrawable.panel(dp(c, 28)));
        box.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        for (View v : children) if (v != null) box.addView(v);
        return box;
    }

    static LinearLayout.LayoutParams lp(Context c, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topMargin);
        return p;
    }

    static LinearLayout.LayoutParams weight(Context c, float w, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.topMargin = dp(c, topMargin);
        return p;
    }

    static LinearLayout.LayoutParams fill(Context c, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        p.topMargin = dp(c, topMargin);
        return p;
    }

    static View spacer(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, h)));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f))));
        return v;
    }

    /* ------------------------------------------------------------ typography */

    static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        // 深色模式下这层柔和黑投影是必要的：照片透出来，白字要靠它压住背景。
        // 但浅色模式是深字配浅底，投影会在笔画周围糊出一圈半透明描边——观感就是"字发虚"。
        if (LIGHT) {
            t.setShadowLayer(0f, 0f, 0f, 0);
        } else {
            t.setShadowLayer(dp(c, 4f), 0f, dp(c, 1.2f), Color.parseColor("#CC000000"));
        }
        return t;
    }

    static TextView screenTitle(Context c, String s) {
        return text(c, s, 28f, TEXT, true);
    }

    static TextView sectionTitle(Context c, String s) {
        TextView t = text(c, s, T_BODY, MUTED, true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    static TextView title(Context c, String s) {
        return text(c, s, T_SUBHEAD, TEXT, true);
    }

    /** Long account names must stay on one line; wrapping wrecks the card rhythm. */
    static TextView oneLineName(Context c, String s) {
        TextView t = text(c, s, T_SUBHEAD, TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static TextView label(Context c, String s) {
        return text(c, s, T_BODY, MUTED, false);
    }

    static TextView mono(Context c, String s, float size) {
        TextView t = text(c, s, size, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    /** Monospace value on a dark glass strip, so model lists stay readable over bright artwork. */
    static TextView monoStrip(Context c, String s, float size) {
        TextView t = mono(c, s, size);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setBackground(round(c, SURFACE_2, 10));
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return t;
    }

    /* --------------------------------------------------------------- 按钮规范
     *
     * 类型（按强调递减），本 App 只用这五档，别再自造第六种：
     *   · Filled      primaryButton      最重要那个动作，一屏只许一个
     *   · Tonal       accentChipButton   配合主操作的一组
     *   · Outlined    outlineButton      次要、并列的选择
     *   · Text        ghostButton / barButton   最弱，栏内和行内
     *   · Destructive destructiveButton  危险操作，独立红系
     *
     * 状态：enabled / pressed(果冻) / disabled / loading 都要有定义。
     *   disabled 和 loading 最容易被漏，已内建 —— 见 setEnabledState / setLoading。
     *   focus / hover 由系统 Ripple 覆盖。
     *
     * 尺寸：最小触摸目标 48dp（无障碍硬要求，不许更小）；
     *   高度三档 40 / 48 / 56；圆角全 App 统一胶囊。
     * 文案：动词开头，2~4 个字（"保存 / 删除 / 发送"，不写"确定"）。
     */

    static final int TOUCH_MIN_DP = 48;
    static final int BTN_H_SMALL = 40;
    static final int BTN_H_MEDIUM = 48;
    static final int BTN_H_LARGE = 56;

    /** 触摸目标兜底：低于 48dp 的按钮一律撑到 48dp。 */
    private static void touchTarget(TextView t, Context c) {
        t.setMinHeight(dp(c, TOUCH_MIN_DP));
        t.setMinimumHeight(dp(c, TOUCH_MIN_DP));
    }

    /** 启用/禁用。禁用 = 半透明 + 不响应点击 + 不做果冻。 */
    static void setEnabledState(TextView b, boolean on) {
        if (b == null) return;
        b.setEnabled(on);
        b.setClickable(on);
        b.setAlpha(on ? 1f : 0.45f);
    }

    /**
     * 加载状态：文案换成进行中，期间不响应点击，松手后状态如实描述。
     * 传 loading=false 会自动还原原来的文案（存在 tag 里）。
     */
    static void setLoading(TextView b, boolean loading, String busyText) {
        if (b == null) return;
        if (loading) {
            if (b.getTag() == null) b.setTag(b.getText());
            b.setText(busyText == null ? "处理中…" : busyText);
        } else {
            Object old = b.getTag();
            if (old instanceof CharSequence) b.setText((CharSequence) old);
        }
        b.setEnabled(!loading);
        b.setClickable(!loading);
        b.setAlpha(loading ? 0.7f : 1f);
    }

    /** 危险操作：删除、清空、移除。独立红系，不跟主色混。 */
    static TextView destructiveButton(Context c, String s) {
        TextView t = text(c, s, T_BUTTON, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        touchTarget(t, c);
        GradientDrawable bg = roundStroke(c,
                Color.argb(226, Color.red(RED), Color.green(RED), Color.blue(RED)),
                20, whiteA(ALPHA_4));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), bg, null));
        t.setClickable(true);
        pressable(t);
        return t;
    }

    /* ------------------------------------------------------------ 导航层 *//**
     *导航层玻璃栏：左返回、中标题（可带副标题）、右边调用方继续 addView。 *内容层不许用它——内容一律走 SURFACE。 */
    static LinearLayout appBar(Context c, TextView back, String title, String subtitle) {
        LinearLayout bar = row(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(navGlass(c, 22));
        bar.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        if (back != null) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.rightMargin = dp(c, 4);
            bar.addView(back, bp);
        }
        LinearLayout titleCol = column(c);
        titleCol.setGravity(Gravity.CENTER);
        TextView t = text(c, title, T_SUBHEAD, TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleCol.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) titleCol.addView(label(c, subtitle), lp(c, 2));
        bar.addView(titleCol, weight(c, 1f, 0));
        return bar;
    }

    /**
     * 导航层玻璃。API 33+ 走 AGSL 折射（液体玻璃），更低或编译失败时内部自动降级成半透明玻璃。
     */
    static android.graphics.drawable.Drawable navGlass(Context c, int radiusDp) {
        return new NavGlassDrawable(c, dp(c, radiusDp));
    }

    static LinearLayout appBar(Context c, String title) {
        return appBar(c, null, title, null);
    }

    /** 栏内按钮：不带自己的面，只有文字和点按反馈（导航层内部专用）。 */
    static TextView barButton(Context c, String s) {
        TextView t = text(c, s, T_BUTTON, TEXT, false);
        // 必须居中：栏内按钮常被 weight 拉成等宽，不居中文字就贴左边，看着"歪"
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        touchTarget(t, c);
        t.setClickable(true);
        pressable(t);
        // "‹ 返回"里那个箭头读屏会念成怪符号，这里统一换成"返回"
        if (s != null && s.startsWith("\u2039")) a11y(t, "返回");
        t.setBackground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE), null, null));
        return t;
    }

    /* --------------------------------------------------------------- 分段选择器 *//**
     *分段选择器：一排选项里选一个。外形走导航层玻璃，选中那段实心。 *用于"多选一且选项少"的场景（≤5个），选项多了该用列表。 */
    interface OnSegment {
        void onPick(int index);
    }

    static LinearLayout segmented(Context c, String[] labels, int selected, OnSegment onPick) {
        LinearLayout bar = row(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(navGlass(c, 22));
        int pad = dp(c, 4);
        bar.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            TextView t = text(c, labels[i], T_BODY, on ? TEXT : MUTED, on);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
            touchTarget(t, c);
            t.setBackground(on
                    ? roundStroke(c, SURFACE_2, 999, SURFACE_STROKE)
                    : new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null));
            t.setClickable(true);
            if (on) t.setSelected(true);
            a11y(t, labels[i] + (on ? "，已选中" : ""));
            t.setOnClickListener(v -> onPick.onPick(idx));
            bar.addView(t, weight(c, 1f, 0));
        }
        return bar;
    }

    /* ------------------------------------------------------------- 分割按钮 *//**
     *分割按钮：左边主操作，右边一个▾展开次要选项。 *用在"有一个主用法、还有几个变体"的地方——比堆两个按钮省位置。 */
    static LinearLayout splitButton(Context c, String mainLabel, final String[] more,
                                    final Runnable onMain, final OnSegment onMore) {
        LinearLayout bar = row(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(GlassDrawable.neutral(dp(c, 20), true));

        TextView main = text(c, mainLabel, T_BUTTON, TEXT, true);
        main.setGravity(Gravity.CENTER);
        main.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        touchTarget(main, c);
        main.setClickable(true);
        main.setOnClickListener(v -> onMain.run());
        pressable(main);
        bar.addView(main, weight(c, 1f, 0));

        android.view.View divider = new android.view.View(c);
        divider.setBackgroundColor(SURFACE_STROKE);
        bar.addView(divider, new LinearLayout.LayoutParams(dp(c, 1),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView caret = text(c, "▾", T_BUTTON, TEXT, false);
        caret.setGravity(Gravity.CENTER);
        caret.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        touchTarget(caret, c);
        caret.setClickable(true);
        a11y(caret, "更多选项");
        caret.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(c, caret);
            for (int i = 0; more != null && i < more.length; i++) {
                menu.getMenu().add(0, i, i, more[i]);
            }
            menu.setOnMenuItemClickListener(item -> {
                if (onMore != null) onMore.onPick(item.getItemId());
                return true;
            });
            menu.show();
        });
        pressable(caret);
        bar.addView(caret);
        return bar;
    }

    /* --------------------------------------------------------------- 无障碍 *//**
     *读屏标签。图标按钮、纯符号按钮必须给，否则读屏只会念"按钮"。 */
    static void a11y(View v, String desc) {
        if (v != null && desc != null && !desc.isEmpty()) v.setContentDescription(desc);
    }

    /* ----------------------------------------------------------------- chips */

    static TextView chip(Context c, String s) {
        TextView t = text(c, s, T_SMALL, TEXT, false);
        t.setBackground(roundStroke(c, GLASS_HI, 999, GLASS_STROKE));
        t.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
        return t;
    }

    /** Status pill with a coloured dot, used for "运行中 / 已停止". */
    /**
     * 能力标签专用的小胶囊：比 {@link #pill} 更紧凑。
     *
     * 为什么单独做一个：pill 是给状态用的（"● 已启用"、"调不通"），
     * 自带圆点、13 号字、左右 13dp 内边距——摆在模型行的能力标签位置时，
     * 三个标签（上下文/输出/工具）就撑满一整行，又长又占高度。
     * 这里去掉圆点、缩到 11 号字、内边距减半，一行能放四五个。
     */
    static TextView tag(Context c, String s) {
        TextView t = text(c, s, T_CAPTION, MUTED, false);
        t.setBackground(round(c, TAG_BG, 6));
        t.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        return t;
    }

    static TextView pill(Context c, String s, int fg, int bg) {
        TextView t = text(c, "●  " + s, T_BODY, fg, true);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE),
                GlassDrawable.dark(dp(c, 999), false), null));
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return t;
    }

    static TextView stat(Context c, String label, String value) {
        android.text.SpannableString sp = new android.text.SpannableString(value + " " + label);
        sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, value.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = text(c, "", T_SMALL, MUTED, false);
        t.setText(sp);
        t.setBackground(roundStroke(c, blackA(ALPHA_5), 999, GLASS_STROKE));
        t.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
        return t;
    }

    /** Big number + tiny label, the "99.2 Mbps" pattern used all over concept designs. */
    static LinearLayout bigStat(Context c, String value, String label) {
        LinearLayout col = column(c);
        col.addView(text(c, value, 26f, TEXT, true));
        TextView l = text(c, label, T_CAPTION, MUTED, false);
        l.setLetterSpacing(0.1f);
        col.addView(l, lp(c, 2));
        return col;
    }

    /* --------------------------------------------------------------- buttons */

    private static TextView clickable(Context c, String s, float size, int fg, int bg, int stroke) {
        TextView t = text(c, s, size, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        GradientDrawable bgDrawable = stroke == 0 ? round(c, bg, 14) : roundStroke(c, bg, 14, stroke);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), bgDrawable, null));
        t.setClickable(true);
        return t;
    }

    static TextView primaryButton(Context c, String s) {
        // 主按钮压在玻璃上：深色模式白字没问题；浅色模式的玻璃是白的，白字就"白底白字"看不见了
        // （截图里「停止网关」就是这样）。所以浅色下换成深色字。
        TextView t = text(c, s, T_BUTTON, LIGHT ? Color.WHITE : Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        android.graphics.drawable.Drawable face = LIGHT
                ? solidAccent(dp(c, 20))          // 浅色：实心主题色，最像"主操作"
                : GlassDrawable.liquid(dp(c, 20), true, true);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE),
                face, null));
        t.setClickable(true);
        // 刻意不设 setElevation：按钮背景是半透明玻璃，Z 轴一高、系统投影会透过本体显出来
        // （表现为"旁边多一个透明的东西"，见 Jelly.LIFT_DP 的注释）。
        // 体感交给 GlassDrawable 自画的那层阴影，视觉自洽。
        touchTarget(t, c);
        pressable(t);
        return t;
    }

    /** 实心主题色圆角底（浅色模式的主按钮用）。 */
    private static android.graphics.drawable.Drawable solidAccent(int radiusPx) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        d.setColors(new int[]{mix(ACCENT, 0xFFFFFFFF, 0.08f), mix(ACCENT, 0xFF000000, 0.10f)});
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView outlineButton(Context c, String s) {
        TextView t = text(c, s, T_BUTTON, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(whiteA(ALPHA_3)),
                GlassDrawable.neutral(dp(c, 20), true, LIGHT), null));
        t.setClickable(true);
        touchTarget(t, c);
        pressable(t);
        return t;
    }

    static TextView ghostButton(Context c, String s) {
        TextView t = text(c, s, T_BODY, ACCENT_LIGHT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        t.setClickable(true);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE),
                GlassDrawable.dark(dp(c, 14), false), null));
        return t;
    }

    /** Backwards-compatible shorthand used by the older screens. */
    static TextView button(Context c, String s, boolean primary) {
        return primary ? primaryButton(c, s) : outlineButton(c, s);
    }

    static TextView accentChipButton(Context c, String s) {
        // 深色下沿用浅色主题文字；浅色下主题文字本来就压深了，直接用它当"强调按钮"的字色，
        // 底仍是玻璃 —— 这样它和旁边的普通按钮区分得开，又不会跳出整体配色。
        TextView t = text(c, s, T_BUTTON, ACCENT_LIGHT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE),
                GlassDrawable.liquid(dp(c, 18), false, true), null));
        t.setClickable(true);
        pressable(t);
        return t;
    }

    /* ------------------------------------------------------- iOS-style motion */

    /**
     * Press behaviour borrowed from iOS: a fast, small scale-down with a dimming overlay, then a
     * spring back that slightly overshoots. Returns false so the click listener still fires.
     */
    /**
     * Jelly press, the iOS rubber-band feel:
     *   - touch down: the control squashes (wider + shorter) and dims a little
     *   - while held: it follows the finger with damping and stretches along the drag, so you can
     *     literally pull it around
     *   - release: it springs back with a visible overshoot, then one small counter-bounce
     * Returns false from the touch listener so click handling keeps working.
     */
    static void pressable(View v) {
        // 果冻手感统一走 Jelly：真阻尼弹簧，按压缩放 + 松手晃 2~3 下 + 跟手拉扯
        Jelly.attach(v);
    }

    /* ---------------------------------------------------------------- dialogs */

    /** Dialog shell that matches the glass screens instead of the platform light theme. */
    static android.app.Dialog dialog(android.app.Activity a, String title, View content,
                                     String negative, Runnable onNegative,
                                     String positive, Runnable onPositive) {
        return dialog(a, title, content, negative, onNegative, positive, onPositive, null);
    }

    /**
     * Same dialog, plus an optional handler for the hardware BACK key: level-two screens can step
     * back to their parent instead of dropping straight to the home screen.
     */
    static android.app.Dialog dialog(android.app.Activity a, String title, View content,
                                     String negative, Runnable onNegative,
                                     String positive, Runnable onPositive,
                                     final Runnable onBack) {
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = column(a);
        box.setBackground(GlassDrawable.panel(dp(a, 28)));
        int pad = dp(a, 20);
        box.setPadding(pad, pad, pad, dp(a, 24));
        if (title != null && !title.isEmpty()) {
            box.addView(text(a, title, 19f, TEXT, true));
        }
        if (content != null) {
            int cap = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.52f);
            View body = content;
            if (body instanceof MaxHeightScrollView) {
                ((MaxHeightScrollView) body).setMaxHeight(cap);
            } else {
                MaxHeightScrollView wrap = new MaxHeightScrollView(a);
                wrap.setMaxHeight(cap);
                wrap.addView(body, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                body = wrap;
            }
            box.addView(body, lp(a, 16));
        }
        LinearLayout actions = row(a);
        actions.setGravity(Gravity.END);
        if (negative != null) {
            TextView cancel = ghostButton(a, negative);
            cancel.setOnClickListener(v -> {
                d.dismiss();
                if (onNegative != null) onNegative.run();
            });
            actions.addView(cancel);
        }
        if (positive != null) {
            TextView ok = primaryButton(a, positive);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = dp(a, 10);
            ok.setOnClickListener(v -> {
                d.dismiss();
                if (onPositive != null) onPositive.run();
            });
            actions.addView(ok, plp);
        }
        box.addView(actions, lp(a, 20));
        d.setContentView(box);
        // 这里**不再**无条件加 FLAG_SECURE。
        //
        // 原来这个工厂给所有对话框都加了，本意是"TokenAdd / ProviderEditor 里的凭据
        // 不要留在最近任务缩略图里"。但它是通用工厂——检测进度、任务结果、
        // 账号详情、各种确认框全从这里出，于是**整个应用的弹窗都不能截屏**，
        // 用户想截图反馈问题直接被挡（真机反馈确认过）。
        //
        // 正确做法：由真正含凭据的调用方自己声明。见 secureDialog()。
        if (onBack != null) {
            d.setCancelable(true);
            d.setOnKeyListener((dialog, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    dialog.dismiss();
                    onBack.run();
                    return true;
                }
                return false;
            });
        }
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams wp = w.getAttributes();
            wp.width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.92f);
            wp.dimAmount = 0.65f;
            w.setAttributes(wp);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.CENTER);
        }
        d.show();
        return d;
    }

    /** Scrolling text body (used for the one-tap task / check reports). */
    static android.widget.ScrollView scroller(android.app.Activity a, View content) {
        android.widget.ScrollView sv = new MaxHeightScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content);
        return sv;
    }

    /** One tappable row for dialog lists (account types, provider presets). */
    static LinearLayout listRow(Context a, String title, String subtitle) {
        LinearLayout row = column(a);
        row.setBackground(GlassDrawable.panel(dp(a, 18)));
        int pad = dp(a, 14);
        row.setPadding(pad, pad, pad, pad);
        row.addView(text(a, title, 15f, TEXT, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = label(a, subtitle);
            sub.setTextSize(12f);
            row.addView(sub, lp(a, 4));
        }
        row.setClickable(true);
        pressable(row);
        return row;
    }

    /** Dark, glass-styled text field. */
    static android.widget.EditText field(Context a, String hint, String value, boolean multiline) {
        android.widget.EditText e = new android.widget.EditText(a);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(14f);
        if (multiline) {
            e.setMinLines(2);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        e.setBackground(roundStroke(a, GLASS_HI, 14, GLASS_STROKE));
        int pad = dp(a, 14);
        e.setPadding(pad, pad, pad, pad);
        return e;
    }

    static android.widget.Switch toggle(Context a, String label, boolean checked) {
        android.widget.Switch s = new android.widget.Switch(a);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setChecked(checked);
        return s;
    }

    /* ------------------------------------------------------- provider visuals */

    static int typeColor(String type) {
        if ("workbuddy".equals(type)) return Color.parseColor("#3B82F6");
        if ("codex".equals(type)) return Color.parseColor("#10A37F");
        if ("claude".equals(type) || "anthropic".equals(type)) return Color.parseColor("#D97757");
        if ("loomy".equals(type)) return Color.parseColor("#E4572E");
        if ("trae".equals(type)) return Color.parseColor("#00D0B0");
        if ("antigravity".equals(type)) return Color.parseColor("#4285F4");
        if ("kimi".equals(type)) return Color.parseColor("#111827");
        if ("deepseek".equals(type)) return Color.parseColor("#4D6BFE");
        if ("doubao".equals(type)) return Color.parseColor("#3370FF");
        if ("xai".equals(type)) return Color.parseColor("#1D9BF0");
        if ("cline".equals(type)) return Color.parseColor("#6366F1");
        if ("kilo".equals(type)) return Color.parseColor("#F59E0B");
        if ("ovh".equals(type)) return Color.parseColor("#0050D7");
        if ("gemini".equals(type)) return Color.parseColor("#4285F4");
        return Color.parseColor("#8B5CF6");
    }

    static String typeLabel(String type) {
        if ("workbuddy".equals(type)) return "WorkBuddy";
        if ("trae".equals(type)) return "Trae";
        if ("loomy".equals(type)) return "Loomy";
        if ("antigravity".equals(type)) return "Antigravity";
        if ("codex".equals(type)) return "ChatGPT";
        if ("claude".equals(type)) return "Claude";
        if ("anthropic".equals(type)) return "Anthropic";
        if ("gemini".equals(type)) return "Gemini";
        if ("kimi".equals(type)) return "Kimi";
        if ("deepseek".equals(type)) return "DeepSeek";
        if ("doubao".equals(type)) return "豆包";
        if ("xai".equals(type)) return "Grok";
        if ("cline".equals(type)) return "Cline";
        if ("kilo".equals(type)) return "Kilo 免费池";
        if ("ovh".equals(type)) return "OVH 免费池";
        return "OpenAI 兼容";
    }

    /** Round avatar with the vendor initial. */
    /** Explicit ASCII initial per vendor: a CJK first char inside a small circle reads badly. */
    static String avatarInitial(String type) {
        if (type == null) return "?";
        switch (type) {
            case "workbuddy":
                return "W";
            case "trae":
                return "T";
            case "loomy":
                return "L";
            case "antigravity":
                return "A";
            case "kimi":
                return "K";
            case "xai":
                return "G";
            case "claude":
                return "C";
            case "codex":
                return "G";
            case "gemini":
                return "G";
            case "anthropic":
                return "A";
            case "deepseek":
                return "D";
            case "doubao":
                return "豆";
            case "openai":
                return "O";
            default:
                return type.isEmpty() ? "?" : type.substring(0, 1).toUpperCase();
        }
    }

    static TextView avatar(Context c, String type) {
        TextView t = text(c, avatarInitial(type), T_SUBHEAD, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        int base = mute(typeColor(type));
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{base, blend(base, Color.WHITE, 0.35f)});
        d.setCornerRadius(dp(c, 999));
        d.setStroke(dp(c, 1), GLASS_STROKE);
        t.setBackground(d);
        a11y(t, typeLabel(type) + " 账号");
        return t;
    }

    /**
     * 把配色压进主题的调子：色相保留（还能一眼区分厂商），只压饱和度和明度。
     * 直接换成一水儿绿色会丢掉厂商辨识度，压饱和才两头都占。
     */
    static int mute(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.30f);
        hsv[2] = Math.min(Math.max(hsv[2], 0.44f), 0.62f);
        return Color.HSVToColor(hsv);
    }

    static int blend(int a, int b, float ratio) {
        return Color.rgb(
                (int) (Color.red(a) * (1 - ratio) + Color.red(b) * ratio),
                (int) (Color.green(a) * (1 - ratio) + Color.green(b) * ratio),
                (int) (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio));
    }
}
