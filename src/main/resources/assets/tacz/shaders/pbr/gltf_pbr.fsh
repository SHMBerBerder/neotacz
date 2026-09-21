#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:light.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D BaseColorSampler;
uniform sampler2D MetallicRoughnessSampler;
uniform sampler2D NormalSampler;
uniform sampler2D OcclusionSampler;
uniform sampler2D EmissiveSampler;
uniform sampler2D BaseColorFactorSampler;
uniform sampler2D EmissiveFactorSampler;
uniform sampler2D PbrParametersSampler;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec3 vertexViewPosition;
layout(location = 3) in vec3 vertexViewNormal;
layout(location = 4) in vec4 vertexColor;
layout(location = 5) in vec4 lightMapColor;
layout(location = 6) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

const float PI = 3.14159265359;
const float MIN_ROUGHNESS = 0.045;

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

vec3 srgb_to_linear(vec3 color) {
    vec3 low = color / 12.92;
    vec3 high = pow((color + 0.055) / 1.055, vec3(2.4));
    return mix(high, low, lessThanEqual(color, vec3(0.04045)));
}

vec3 linear_to_srgb(vec3 color) {
    color = max(color, vec3(0.0));
    vec3 low = color * 12.92;
    vec3 high = 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, lessThanEqual(color, vec3(0.0031308)));
}

mat3 cotangent_frame(vec3 normal, vec3 position, vec2 uv) {
    vec3 dp1 = dFdx(position);
    vec3 dp2 = dFdy(position);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, normal);
    vec3 dp1perp = cross(normal, dp1);
    vec3 tangent = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 bitangent = dp2perp * duv1.y + dp1perp * duv2.y;
    float invMax = inversesqrt(max(max(dot(tangent, tangent), dot(bitangent, bitangent)), 0.000001));
    return mat3(tangent * invMax, bitangent * invMax, normal);
}

vec3 sample_normal(vec3 normal, vec3 position, vec2 uv, float normalScale) {
    vec3 tangentNormal = texture(NormalSampler, uv).xyz * 2.0 - 1.0;
    tangentNormal.xy *= normalScale;
    mat3 tbn = cotangent_frame(normal, position, uv);
    return normalize(tbn * tangentNormal);
}

float distribution_ggx(vec3 normal, vec3 halfVector, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float nDotH = saturate(dot(normal, halfVector));
    float nDotH2 = nDotH * nDotH;
    float denom = nDotH2 * (a2 - 1.0) + 1.0;
    return a2 / max(PI * denom * denom, 0.000001);
}

float geometry_schlick_ggx(float nDotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return nDotV / max(nDotV * (1.0 - k) + k, 0.000001);
}

float geometry_smith(vec3 normal, vec3 viewDir, vec3 lightDir, float roughness) {
    float nDotV = saturate(dot(normal, viewDir));
    float nDotL = saturate(dot(normal, lightDir));
    return geometry_schlick_ggx(nDotV, roughness) * geometry_schlick_ggx(nDotL, roughness);
}

vec3 fresnel_schlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(1.0 - saturate(cosTheta), 5.0);
}

vec3 evaluate_light(vec3 normal, vec3 viewDir, vec3 lightDir, vec3 baseColor, float metallic, float roughness, vec3 f0, vec3 radiance) {
    vec3 halfVector = normalize(viewDir + lightDir);
    float nDotL = saturate(dot(normal, lightDir));
    float nDotV = saturate(dot(normal, viewDir));
    float hDotV = saturate(dot(halfVector, viewDir));

    float distribution = distribution_ggx(normal, halfVector, roughness);
    float geometry = geometry_smith(normal, viewDir, lightDir, roughness);
    vec3 fresnel = fresnel_schlick(hDotV, f0);
    vec3 specular = distribution * geometry * fresnel / max(4.0 * nDotV * nDotL, 0.0001);
    vec3 diffuse = (vec3(1.0) - fresnel) * (1.0 - metallic) * baseColor / PI;
    return (diffuse + specular) * radiance * nDotL;
}

void main() {
    vec4 baseSample = texture(BaseColorSampler, texCoord0);
    vec4 baseColorFactor = texture(BaseColorFactorSampler, vec2(0.5, 0.5));
    vec4 emissiveFactorAndCutoff = texture(EmissiveFactorSampler, vec2(0.5, 0.5));
    float alpha = baseSample.a * baseColorFactor.a * vertexColor.a;

#ifdef GLTF_ALPHA_OPAQUE
    alpha = 1.0;
#endif

#ifdef GLTF_ALPHA_MASK
    if (alpha < emissiveFactorAndCutoff.a) {
        discard;
    }
    alpha = 1.0;
#endif

#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, alpha);
#else
    vec4 pbrParameters = texture(PbrParametersSampler, vec2(0.5, 0.5));
    vec3 metallicRoughness = texture(MetallicRoughnessSampler, texCoord0).rgb;
    float occlusion = texture(OcclusionSampler, texCoord0).r;
    vec3 emissive = srgb_to_linear(texture(EmissiveSampler, texCoord0).rgb) * emissiveFactorAndCutoff.rgb;
    vec3 baseColor = srgb_to_linear(baseSample.rgb) * baseColorFactor.rgb * vertexColor.rgb;
    float metallic = saturate(metallicRoughness.b * pbrParameters.r);
    float roughness = max(MIN_ROUGHNESS, saturate(metallicRoughness.g * pbrParameters.g));
    float normalScale = max(pbrParameters.b * 4.0, 0.0);
    float occlusionStrength = saturate(pbrParameters.a);
    float ambientOcclusion = mix(1.0, occlusion, occlusionStrength);

    vec3 geometricNormal = normalize(vertexViewNormal);
    if (!gl_FrontFacing) {
        geometricNormal = -geometricNormal;
    }
    vec3 normal = sample_normal(geometricNormal, vertexViewPosition, texCoord0, normalScale);
    // Orthographic GUI atlas slots share one view direction regardless of their pixel position.
    vec3 viewDir = abs(ProjMat[3][3]) > 0.5 ? vec3(0.0, 0.0, 1.0) : normalize(-vertexViewPosition);
    vec3 f0 = mix(vec3(0.04), baseColor, metallic);
    vec3 lightColor = max(lightMapColor.rgb, vec3(0.0));
    vec3 light0ViewDirection = normalize(mat3(ModelViewMat) * Light0_Direction);
    vec3 light1ViewDirection = normalize(mat3(ModelViewMat) * Light1_Direction);

    vec3 color = vec3(0.0);
    color += evaluate_light(normal, viewDir, light0ViewDirection, baseColor, metallic, roughness, f0, lightColor);
    color += evaluate_light(normal, viewDir, light1ViewDirection, baseColor, metallic, roughness, f0, lightColor);
    color += baseColor * lightColor * MINECRAFT_AMBIENT_LIGHT * ambientOcclusion;
    color += emissive;

    vec4 encoded = vec4(linear_to_srgb(color), alpha);
#ifdef OIT_ACCUMULATE
    encoded = sampleColorForAccumulation(encoded);
    vec4 fogColor = vec4(FogColor.rgb * encoded.a, FogColor.a);
#else
    vec4 fogColor = FogColor;
#endif
    fragColor = apply_fog(encoded, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
}
