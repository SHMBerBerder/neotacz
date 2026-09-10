#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec3 vertexViewPosition;
out vec3 vertexViewNormal;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord0;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexViewPosition = viewPosition.xyz;
    vertexViewNormal = normalize(transpose(inverse(mat3(ModelViewMat))) * Normal);
    vertexColor = Color * ColorModulator;
    lightMapColor = sample_lightmap(Sampler2, UV2);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}
