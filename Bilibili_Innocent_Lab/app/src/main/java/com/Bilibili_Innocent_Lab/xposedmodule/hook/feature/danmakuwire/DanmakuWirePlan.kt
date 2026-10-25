package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossBridgeMembers
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/**
 * 弹幕 wire 快路径的安装期准备：**字段号探针 + 端到端自检**，一步到位。
 *
 * 探针不硬编码任何字段号：在真实宿主类上构造"只设置单个字段"的合成消息并序列化，
 * 从 wire 字节反推字段号（protobuf-lite 生成代码不暴露字段号 API，序列化反推是唯一
 * 可信来源）。任何一步解析失败、候选不唯一、字段号互异校验不过，都返回 null——
 * 安装器退回既有反射路径，绝不猜。
 *
 * 自检在安装期（Hook 注册之前）做三件事：
 * 1. JVM 基准扫描合成 reply，逐值核对（含 content 偏移切回原文）；
 * 2. 若 native 库可用，对同一输入做**差分**：scan 拆平结果与 JVM 基准逐元素相等、
 *    rewrite 输出逐字节相等；任何不一致都把 native 标记为不可信（本进程回退 JVM 层）；
 * 3. 改写结果经宿主 `parseFrom` 回读并核对列表条数——"实际核改写结果"红线在 wire
 *    路径上的落点：改写产物真的被宿主解析器接受并产生预期条数。
 *
 * 全程只持有 Class/Method 与一次性合成实例，不缓存任何宿主业务实例。
 */
internal class DanmakuWirePlan private constructor(
    val fields: DanmakuWireFields,
    private val codec: KotlinMossBridgeMembers.JavaReplyCodec,
) {
    fun serialize(reply: Any): ByteArray? =
        runCatching { codec.serialize(reply) }.getOrNull()

    fun parse(bytes: ByteArray): Any? =
        runCatching { codec.parse(bytes) }.getOrNull()

    internal companion object {
        /** 探针值只要非默认就会被 protobuf 序列化；文本特意含多字节 UTF-8。 */
        private const val PROBE_WEIGHT = 7
        private const val PROBE_TEXT = "探针甲"

        fun resolve(
            replyClass: Class<*>,
            elemClass: Class<*>,
            colorfulClass: Class<*>,
            replyBuilder: ProtobufBuilderPlan,
            elemBuilder: ProtobufBuilderPlan,
            vipValue: Int,
            listCountOf: (Any) -> Int?,
            logError: (String, String) -> Unit,
        ): DanmakuWirePlan? = runCatching {
            val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(replyClass) ?: return null
            val elemToBytes = toByteArrayOf(elemClass) ?: return null
            val colorfulToBytes = toByteArrayOf(colorfulClass) ?: return null
            val emptyElem = defaultInstance(elemClass) ?: return null
            val emptyReply = defaultInstance(replyClass) ?: return null
            val emptyColorful = defaultInstance(colorfulClass) ?: return null

            val setWeight = elemBuilder.method("setWeight", classOf<Int>()) ?: return null
            val setContent = elemBuilder.method("setContent", classOf<String>())
                ?: elemBuilder.method("setContent", classOf<CharSequence>()) ?: return null
            val setColorful = elemBuilder.method("setColorfulValue", classOf<Int>()) ?: return null
            val colorfulBuilder = ProtobufBuilderPlan.resolve(colorfulClass) ?: return null
            val setTypeValue = colorfulBuilder.method("setTypeValue", classOf<Int>()) ?: return null
            val addAllElems = replyBuilder.method("addAllElems", classOf<Iterable<*>>()) ?: return null
            val addAllColorfulSrc = replyBuilder.method("addAllColorfulSrc", classOf<Iterable<*>>()) ?: return null

            // ---- 单字段探针：wire 反推 elem/colorful 内部与 reply 上的字段号 ----
            val elemWeightBytes = serializeElem(elemToBytes, elemBuilder, emptyElem) { setWeight.invoke(it, PROBE_WEIGHT) }
                ?: return null
            val weightField = DanmakuWire.singleVarintField(elemWeightBytes, PROBE_WEIGHT.toLong()) ?: return null
            val elemColorfulBytes = serializeElem(elemToBytes, elemBuilder, emptyElem) { setColorful.invoke(it, vipValue) }
                ?: return null
            val colorfulField = DanmakuWire.singleVarintField(elemColorfulBytes, vipValue.toLong()) ?: return null
            val textBytes = PROBE_TEXT.toByteArray(Charsets.UTF_8)
            val elemTextBytes = serializeElem(elemToBytes, elemBuilder, emptyElem) { setContent.invoke(it, PROBE_TEXT) }
                ?: return null
            val contentField = DanmakuWire.singleDelimitedField(elemTextBytes, textBytes) ?: return null
            val colorfulVip = colorfulBuilder.edit(emptyColorful) { setTypeValue.invoke(it, vipValue) }
            val colorfulVipBytes = runCatching { colorfulToBytes.invoke(colorfulVip) as? ByteArray }.getOrNull()
                ?: return null
            val colorfulTypeField = DanmakuWire.singleVarintField(colorfulVipBytes, vipValue.toLong()) ?: return null

            // reply 级：各挂一条探针条目，按序列化负载逐字节匹配定位列表字段号。
            val probeElem = elemBuilder.edit(emptyElem) { setWeight.invoke(it, PROBE_WEIGHT) }
            val replyElemsBytes = replyBuilder.edit(emptyReply) {
                addAllElems.invoke(it, listOf(probeElem))
            }.let(codec::serialize) ?: return null
            val elemsMatches = DanmakuWire.delimitedFieldsWithPayload(replyElemsBytes, elemWeightBytes)
            val replySrcBytes = replyBuilder.edit(emptyReply) {
                addAllColorfulSrc.invoke(it, listOf(colorfulVip))
            }.let(codec::serialize) ?: return null
            val srcMatches = DanmakuWire.delimitedFieldsWithPayload(replySrcBytes, colorfulVipBytes)
            if (elemsMatches.size != 1 || srcMatches.size != 1) return null
            val fields = DanmakuWireFields(
                elemsField = elemsMatches[0],
                weightField = weightField,
                colorfulField = colorfulField,
                contentField = contentField,
                colorfulSrcField = srcMatches[0],
                colorfulTypeField = colorfulTypeField,
            )
            if (!fields.isValid()) return null

            val plan = DanmakuWirePlan(fields, codec)
            val selfTestOk = selfTest(
                plan, replyBuilder, elemBuilder, colorfulBuilder,
                emptyReply, emptyElem, emptyColorful,
                setWeight, setContent, setColorful, setTypeValue,
                addAllElems, addAllColorfulSrc, vipValue, listCountOf, logError,
            )
            if (!selfTestOk) return null
            plan
        }.getOrNull()

        /** `toByteArray` 声明在 AbstractMessageLite，必须走含继承的 `getMethod`（真机教训）。 */
        private fun toByteArrayOf(owner: Class<*>): Method? =
            runCatching { owner.getMethod("toByteArray") }.getOrNull()
                ?.takeIf { !it.isStatic && it.returnType == classOf<ByteArray>() }

        /** 单字段探针消息：edit + 自定义变更 + 序列化，一步到位。 */
        private fun serializeElem(
            toBytes: Method,
            plan: ProtobufBuilderPlan,
            empty: Any,
            change: (Any) -> Unit,
        ): ByteArray? = runCatching {
            toBytes.invoke(plan.edit(empty, change)) as? ByteArray
        }.getOrNull()

        private fun defaultInstance(owner: Class<*>): Any? {
            val getter = KavaMemberLookup.methodOrNull(owner, "getDefaultInstance")
                ?.takeIf { it.isStatic && it.parameterCount == 0 } ?: return null
            return runCatching { getter.invoke(null) }.getOrNull()
        }

        private fun selfTest(
            plan: DanmakuWirePlan,
            replyBuilder: ProtobufBuilderPlan,
            elemBuilder: ProtobufBuilderPlan,
            colorfulBuilder: ProtobufBuilderPlan,
            emptyReply: Any,
            emptyElem: Any,
            emptyColorful: Any,
            setWeight: Method,
            setContent: Method,
            setColorful: Method,
            setTypeValue: Method,
            addAllElems: Method,
            addAllColorfulSrc: Method,
            vipValue: Int,
            listCountOf: (Any) -> Int?,
            logError: (String, String) -> Unit,
        ): Boolean {
            // 三条弹幕：高权重普通 / 低权重彩字（将被删）/ 中权重彩字带正文（保留+改色）。
            val elemA = elemBuilder.edit(emptyElem) { setWeight.invoke(it, 9) }
            val elemB = elemBuilder.edit(emptyElem) {
                setWeight.invoke(it, 1)
                setColorful.invoke(it, vipValue)
            }
            val elemC = elemBuilder.edit(emptyElem) {
                setWeight.invoke(it, 5)
                setColorful.invoke(it, vipValue)
                setContent.invoke(it, PROBE_TEXT)
            }
            val srcVip = colorfulBuilder.edit(emptyColorful) { setTypeValue.invoke(it, vipValue) }
            val synthetic = replyBuilder.edit(emptyReply) {
                addAllElems.invoke(it, listOf(elemA, elemB, elemC))
                addAllColorfulSrc.invoke(it, listOf(srcVip, emptyColorful))
            }
            val bytes = plan.serialize(synthetic) ?: return false
            val oracle = DanmakuWire.scan(bytes, plan.fields) ?: return false
            if (oracle.elems.size != 3 || oracle.srcKinds.size != 2) return false
            if (oracle.elems[0].weight != 9 || oracle.elems[1].colorful != vipValue) return false
            val content = oracle.elems[2]
            if (!content.hasContent || content.contentLen != PROBE_TEXT.toByteArray(Charsets.UTF_8).size) return false
            if (String(bytes, content.contentStart, content.contentLen, Charsets.UTF_8) != PROBE_TEXT) return false
            if (oracle.srcKinds[0] != vipValue || oracle.srcKinds[1] != null) return false

            val keepElem = booleanArrayOf(true, false, true)
            val recolorElem = booleanArrayOf(false, false, true)
            val keepSrc = booleanArrayOf(false, true)
            val jvmRewritten = DanmakuWire.rewrite(bytes, plan.fields, keepElem, recolorElem, keepSrc)
                ?: return false
            var chosen = jvmRewritten
            if (DanmakuWireNative.isAvailable) {
                val nativeScan = DanmakuWireNative.scan(bytes, plan.fields)
                if (nativeScan == null || !nativeScan.contentEquals(DanmakuWire.flatten(oracle))) {
                    DanmakuWireNative.markUntrusted()
                    logError(
                        "danmaku_wire_native_mismatch",
                        "[BIL] 弹幕 wire 自检：native 扫描与 JVM 基准不一致，本进程回退 JVM 扫描"
                    )
                } else {
                    val nativeRewritten =
                        DanmakuWireNative.rewrite(bytes, plan.fields, keepElem, recolorElem, keepSrc)
                    if (nativeRewritten == null || !nativeRewritten.contentEquals(jvmRewritten)) {
                        DanmakuWireNative.markUntrusted()
                        logError(
                            "danmaku_wire_native_mismatch",
                            "[BIL] 弹幕 wire 自检：native 改写与 JVM 基准不一致，本进程回退 JVM 扫描"
                        )
                    } else {
                        chosen = nativeRewritten
                    }
                }
            }
            // wire 级复检：保留条数、改色值、正文随行。
            val rescan = DanmakuWire.scan(chosen, plan.fields) ?: return false
            if (rescan.elems.size != 2 || rescan.srcKinds.size != 1) return false
            if (rescan.elems[0].weight != 9 || rescan.elems[1].colorful != 0) return false
            if (!rescan.elems[1].hasContent) return false
            // 宿主解析器口径复检（红线：实际核改写结果）。
            val parsed = plan.parse(chosen) ?: return false
            return listCountOf(parsed) == 2
        }
    }
}
