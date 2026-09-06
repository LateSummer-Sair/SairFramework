package sair;

import java.awt.Color;

/**
 * 全局颜色常量表。
 * <p>
 * 职责:集中定义控制台/UI 各输出场景(加载 boot/exection/mod 日志、错误、
 * 分割线、路径提示、帮助等)所用的 java.awt.Color,供 SairCons/ConsFrame 等使用。
 * <p>
 * 架构角色:纯常量容器,无逻辑;插件可直接读写这些字段来定制配色。
 * <p>
 * 线程安全:全部为可变静态字段,多线程同时读写同一字段存在可见性竞争;
 * 惯例是启动期写入一次、运行期只读,不做同步(保持旧版行为)。
 * <p>
 * 二进制兼容约束(重要,不可改):这些字段是旧 0.5.3 插件直接读写过的
 * 公开静态 Color 字段——字段名与类型均不可改,更不可加 final(插件运行时
 * 覆盖颜色依赖其可变性);类名 FCM 不可改。
 */
public class FCM {
    /**
     * 加载 bootlib(旧目录)时的日志颜色(默认黑)
     */
    public static Color loadBoot_Color = Color.BLACK;
    /**
     * 加载 exection(插件)时的日志颜色(默认橙)
     */
    public static Color loadExection_Color = Color.ORANGE;
    /**
     * 加载 mod 依赖库时的日志颜色(默认白)
     */
    public static Color loadMod_Color = Color.WHITE;

    /**
     * 插件路径信息输出颜色(默认黄)
     */
    public static Color EXECTION_pathInfo_Color = Color.YELLOW;
    /**
     * 插件帮助信息输出颜色(默认粉)
     */
    public static Color EXECTION_help_Color = Color.PINK;

    /**
     * 错误信息输出颜色(默认红):启动期与运行期错误日志统一使用
     */
    public static Color Error_Color = Color.RED;
    /**
     * 分割线输出颜色(默认绿)
     */
    public static Color split_Color = Color.GREEN;
}
