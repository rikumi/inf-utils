package com.nyaa.infutils.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

import java.util.ArrayList;
import java.util.List;

@Config(name = "nyaa-infinite-infernal-utils")
public class ModConfig implements ConfigData {

    @Comment("启用或禁用整个模组")
    public boolean modEnabled = true;

    @Comment("调试模式，启用额外日志")
    public boolean debugMode = false;

    // ==================== Feature: Health Bar ====================

    @Comment("取消生命条凋零效果（整体变黑），防止看不清生命值，默认开启")
    public boolean disableWitherHealthDarken = true;

    // ==================== Feature: Summon Glow ====================

    @ConfigEntry.Gui.CollapsibleObject
    public SummonGlowSettings summonGlow = new SummonGlowSettings();

    public static class SummonGlowSettings {
        @Comment("将召唤物（隐形盔甲架）显示为发光")
        public boolean enabled = true;

        public enum SummonGlowMode {
            /** 原版轮廓光 */
            OUTLINE,
            /** 自身亮度：渲染实体本身更亮而非轮廓 */
            BRIGHTNESS
        }

        @Comment("发光模式：OUTLINE=原版轮廓光；BRIGHTNESS=强制全亮（光影下效果明显）")
        public SummonGlowMode glowMode = SummonGlowMode.BRIGHTNESS;

        @ConfigEntry.ColorPicker
        @Comment("轮廓光颜色（RRGGBB），仅OUTLINE模式使用")
        public int glowColor = 0x00FFFF;

        @Comment("仅将有装备的隐形盔甲架视为召唤物")
        public boolean requireEquipment = true;
    }

    // ==================== Feature: Monster (怪物) Glow ====================

    @ConfigEntry.Gui.CollapsibleObject
    public MonsterGlowSettings monsterGlow = new MonsterGlowSettings();

    public static class MonsterGlowSettings {
        @Comment("使所有怪物发光")
        public boolean enabled = false;

        public enum MonsterGlowMode {
            /** 原版轮廓光 */
            OUTLINE,
            /** 自身亮度：渲染实体本身更亮而非轮廓 */
            BRIGHTNESS
        }

        @Comment("发光模式：OUTLINE=原版轮廓光；BRIGHTNESS=强制全亮（光影下效果明显）")
        public MonsterGlowMode glowMode = MonsterGlowMode.BRIGHTNESS;

        @ConfigEntry.ColorPicker
        @Comment("轮廓光颜色，仅OUTLINE模式使用")
        public int glowColor = 0xFFFFFF;
    }

    // ==================== Feature: Elite (精英怪) Glow ====================

    @ConfigEntry.Gui.CollapsibleObject
    public EliteGlowSettings eliteGlow = new EliteGlowSettings();

    public static class EliteGlowSettings {
        @Comment("使所有精英怪发光")
        public boolean enabled = true;

        @ConfigEntry.ColorPicker
        @Comment("轮廓光颜色")
        public int glowColor = 0xFF0000;
    }

    // ==================== Feature: Attack Curve ====================

    @ConfigEntry.Gui.CollapsibleObject
    public AttackCurveSettings attackCurve = new AttackCurveSettings();

    public static class AttackCurveSettings {
        @Comment("将服务器连续粒子渲染为泰拉瑞亚式发光攻击曲线")
        public boolean enabled = true;

        @Comment("两粒子被视为同曲线的最大距离（格），保持较低避免散乱粒子串联，攻击弧断裂时可调高")
        public float maxSegmentDistance = 1F;

        @Comment("每帧追踪的最大服务器粒子数")
        @ConfigEntry.BoundedDiscrete(min = 16, max = 4096)
        public int maxTrackedParticles = 1024;

        @Comment("粒子位置保留时间（tick）")
        @ConfigEntry.BoundedDiscrete(min = 1, max = 60)
        public int particleLifetimeTicks = 4;

        @Comment("使用粒子自身颜色；关闭则使用下方固定颜色")
        public boolean useParticleColor = true;

        @ConfigEntry.ColorPicker
        @Comment("固定曲线颜色（RRGGBB），关闭使用粒子自身颜色时使用")
        public int curveColor = 0xFFAA33;

        @Comment("发光带宽度（格），中间粗两端细")
        @ConfigEntry.BoundedDiscrete(min = 1, max = 32)
        public float lineWidth = 1.0F;

        @Comment("核心透明度（0-1），1.0=最亮，外层光晕取此值45%")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 1)
        public float opacity = 0.7F;

        @Comment("带宽随粒子间距缩放系数，1=恒定宽度，>1可让稀疏粒子仍呈连续带")
        public float scaleBandWidth = 1.0F;

        @Comment("最小连接粒子数，低于此值视为孤立光点")
        @ConfigEntry.BoundedDiscrete(min = 2, max = 16)
        public int minCurvePoints = 6;

        @Comment("叠加发光/泛光效果")
        public boolean additiveGlow = true;

        @Comment("显示屏幕调试信息（粒子/点/曲线数）")
        public boolean showDebug = false;

        @Comment("隐藏原粒子仅渲染曲线；false时曲线叠加在原粒子上方")
        public boolean replaceParticleRendering = true;

        @Comment("仅将服务器发送的粒子转为曲线；原版本地粒子不受影响")
        public boolean attackOnly = true;

        @Comment("仅转换类型ID包含这些字符串的粒子（空=全部），点击左侧加号增加")
        public List<String> particleTypes = new ArrayList<>();

        @Comment("不转换类型ID包含这些字符串的粒子，点击左侧加号增加")
        public List<String> excludeParticleTypes = new ArrayList<>(List.of("end_gateway", "fishing", "bubble"));
    }

    // ==================== Feature: Region Overlay ====================

    @ConfigEntry.Gui.CollapsibleObject
    public RegionOverlaySettings regionOverlay = new RegionOverlaySettings();

    public static class RegionOverlaySettings {
        @Comment("记住最后显示的区域名并持久显示在 bossbar 下方居中")
        public boolean enabled = true;

        @Comment("水平偏移（像素），相对于屏幕中心")
        public int xOffset = 0;

        @Comment("垂直位置（像素），从屏幕顶部起（默认 20 ≈ bossbar 下方）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 1000)
        public int yOffset = 20;

        @Comment("文字颜色")
        @ConfigEntry.ColorPicker
        public int textColor = 0xFFFFFF;

        @Comment("文字不透明度（0-255）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 255)
        public int textAlpha = 230;

        @Comment("文字阴影")
        public boolean shadow = true;

        @Comment("记录每次区域变化（调试用）")
        public boolean debugLog = false;
    }

    // ==================== Feature: Drop Sound (掉落音效) ====================

    @ConfigEntry.Gui.CollapsibleObject
    public DropSoundSettings dropSound = new DropSoundSettings();

    public static class DropSoundSettings {
        @Comment("屏蔽原版经验增加/升级音效，改为在掉落获得矿物块时播放对应音效，默认开启")
        public boolean enabled = true;
    }

    // ==================== Feature: Sound Replace (Terraria wand SFX) ====================

    @ConfigEntry.Gui.CollapsibleObject
    public SoundReplaceSettings soundReplace = new SoundReplaceSettings();

    public static class SoundReplaceSettings {
        @Comment("将服务器攻击音效替换为泰拉瑞亚式法杖音效")
        public boolean enabled = true;

        @Comment("仅替换ID包含这些子串的音效（空=使用内置过滤，推荐），逗号分隔")
        public List<String> matchFilters = new ArrayList<>();

        @Comment("不替换这些类别的音效（子串，小写），默认保护音乐/唱片/天气/环境/语音")
        public List<String> excludeCategories = new ArrayList<>(List.of("music", "records", "ambient", "weather", "voice"));

        @Comment("音效ID子串→替换音效映射，格式:'子串=magic_item9'，空=自动映射")
        public List<String> mapping = new ArrayList<>();

        @Comment("随机选择映射音效而非固定音效")
        public boolean randomize = true;

        @Comment("替换音效音量倍率（1.0=原音量）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 5)
        public float volume = 2.0F;

        @Comment("替换音效音调倍率（1.0=原音调）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 2)
        public float pitch = 1.0F;

        @Comment("记录每次音效替换（调试用）")
        public boolean verbose = false;

        @Comment("在游戏内显示最近捕获的声音信息（id/分类/是否注册/是否替换），用于排查音效替换问题，默认关闭")
        public boolean debugOverlay = false;
    }

    // ==================== Feature: Mana Display (魔力显示) ====================

    @ConfigEntry.Gui.CollapsibleObject
    public ManaDisplaySettings manaDisplay = new ManaDisplaySettings();

    public static class ManaDisplaySettings {
        @Comment("解析底部动作栏的 MANA 文本，以五角星（★）显示当前/最大魔力，默认开启")
        public boolean enabled = true;

        @Comment("五角星颜色（RRGGBB）")
        @ConfigEntry.ColorPicker
        public int starColor = 0x9933FF;

        @Comment("五角星不透明度（0-255）")
        @ConfigEntry.BoundedDiscrete(min = 0, max = 255)
        public int textAlpha = 255;

        @Comment("五角星阴影")
        public boolean shadow = true;

        @Comment("水平偏移（像素），相对于饱食度条")
        public int xOffset = 0;

        @Comment("垂直偏移（像素），相对于饱食度条上方")
        public int yOffset = 0;

        @Comment("隐藏服务端下发的动作栏（魔力/怒气条），默认开启")
        public boolean hideActionBar = true;
    }
}
