import { readFile, mkdir } from 'node:fs/promises';
import { delimiter, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

// Preparation is explicit; this helper never launches Gradle or the game.
const root = process.cwd();
const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error('Set JAVA_HOME to the project Java 25 JDK.');
const classpathFile = process.env.PUBLIC_MESH_CLASSPATH || 'build/gunpack-tool/runtime-classpath.txt';
let runtime;
try {
  runtime = (await readFile(resolve(root, classpathFile), 'utf8')).trim();
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  throw new Error('Missing runtime classpath. Run ./gradlew -I tools/gunpack/gunpack.init.gradle preparePublicMeshPack first.', { cause: error });
}
if (!runtime) throw new Error('Runtime classpath is empty; rerun preparePublicMeshPack.');
const classes = resolve(root, 'build/public-mesh-pack/classes');
await mkdir(classes, { recursive: true });
const classpath = [classes, resolve(root, 'build/gunpack-tool/classes'), runtime].join(delimiter);
const run = (tool, args) => {
  const result = spawnSync(resolve(javaHome, 'bin', tool), args, { stdio: 'inherit', cwd: root });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
};
run('javac', ['-encoding', 'UTF-8', '-cp', classpath, '-d', classes,
  'tools/gunpack/public_mesh_pack/PublicMeshPackTool.java',
  'tools/gunpack/public_mesh_pack/PublicMeshPackReferences.java',
  'tools/gunpack/public_mesh_pack/PublicMeshPackTest.java']);
const command = process.argv[2] || 'build';
run('java', ['-Xmx1G', '-cp', classpath,
  command === 'test' ? 'com.tacz.guns.tools.gunpack.PublicMeshPackTest' : 'com.tacz.guns.tools.gunpack.PublicMeshPackTool',
  ...(command === 'test' ? [root] : [command, root, ...process.argv.slice(3)])]);
