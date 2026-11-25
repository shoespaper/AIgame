package com.gameneko.aigame.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** 定义引擎对开局模板提案提供的规则和校验。 */
public interface EngineTemplatePolicy {
    /** 返回负责解释规则的引擎标识。 */
    String id();

    /** 返回引擎预置的规则数组，供开局注册和模型参考。 */
    JsonNode defaults();

    /** 返回需要规则引用的素材池与该引擎接受的实体类型映射。 */
    Map<String, String> referenceTypes();

    /** 校验一条待登记的引擎规则。
     * @param rule 包含稳定标识、规则类型和参数的新增规则 JSON 对象
     */
    void validateRule(JsonNode rule);

    /** 校验素材引用的已登记规则是否适用于该素材池。
     * @param group 素材池稳定标识
     * @param itemId 引用该规则的素材稳定标识
     * @param rule 按精确标识找到的规则，定义缺失时为缺失节点
     */
    void validateReference(String group, String itemId, JsonNode rule);
}
