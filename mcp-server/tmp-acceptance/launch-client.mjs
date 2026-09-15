import { spawn } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'

const root = 'D:\\未完成TimeLimit\\残灯花火\\杂七杂八的东西\\.minecraft'
const versionDir = `${root}\\versions\\AIRI`
const json = JSON.parse(readFileSync(`${versionDir}\\AIRI.json`, 'utf8'))

/** Maven coordinate `group:artifact:version[:classifier]` to a libraries path. */
function mavenPath(name) {
  const [group, artifact, version, classifier] = name.split(':')
  const file = `${artifact}-${version}${classifier ? `-${classifier}` : ''}.jar`
  return `${group.replaceAll('.', '/')}/${artifact}/${version}/${file}`
}

const libraries = []
for (const library of json.libraries) {
  if (library.rules) {
    let allowed = false
    for (const rule of library.rules) {
      const osName = rule.os?.name
      const matches = !osName || osName === 'windows'
      if (rule.action === 'allow' && matches)
        allowed = true
      if (rule.action === 'disallow' && matches)
        allowed = false
    }
    if (!allowed)
      continue
  }
  const path = library.downloads?.artifact?.path ?? mavenPath(library.name)
  const full = `${root}\\libraries\\${path.replaceAll('/', '\\')}`
  if (!existsSync(full)) {
    console.error(`missing library: ${full}`)
    continue
  }
  libraries.push(full)
}

// PCL keeps the game jar itself as the last classpath entry.
libraries.push(`${versionDir}\\AIRI.jar`)

const natives = 'C:\\Users\\86130\\AppData\\Roaming\\.minecraft\\bin\\natives'
const args = [
  '-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump',
  `-Djava.library.path=${natives}`,
  `-Djna.tmpdir=${natives}`,
  `-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives}`,
  `-Dio.netty.native.workdir=${natives}`,
  '-Dminecraft.launcher.brand=PCL',
  '-Dminecraft.launcher.version=408',
  '-cp', libraries.join(';'),
  '-DFabricMcEmu=net.minecraft.client.main.Main',
  '-XX:-OmitStackTraceInFastThrow',
  '-Djdk.lang.Process.allowAmbiguousCommands=True',
  '-Dfml.ignoreInvalidMinecraftCertificates=True',
  '-Dfml.ignorePatchDiscrepancies=True',
  '--add-exports', 'cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher=ALL-UNNAMED',
  '-Xmx5324m', '-XX:+UnlockExperimentalVMOptions', '-XX:+UseG1GC',
  '-XX:G1NewSizePercent=20', '-XX:G1ReservePercent=20', '-XX:G1HeapRegionSize=32M',
  '-XX:MaxGCPauseMillis=50', '-XX:+PerfDisableSharedMem', '-XX:MinHeapFreeRatio=25', '-XX:MaxHeapFreeRatio=40',
  '-Dlog4j2.formatMsgNoLookups=true', '-Dstdout.encoding=utf-8', '-Dstderr.encoding=utf-8',
  'net.fabricmc.loader.impl.launch.knot.KnotClient',
  '--username', 'airitest',
  '--version', 'AIRI',
  '--gameDir', versionDir,
  '--assetsDir', `${root}\\assets`,
  '--assetIndex', '17',
  '--uuid', '0000000000003008999A5611E1BEF0B0',
  '--accessToken', '0000000000003008999A5611E1BEF0B0',
  '--clientId', '${clientid}',
  '--xuid', '${auth_xuid}',
  '--userType', 'msa',
  '--versionType', 'PCL',
  '--width', '854', '--height', '480',
  '--quickPlayMultiplayer', 'localhost:25565',
]

const java = 'C:\\Users\\86130\\AppData\\Roaming\\.minecraft\\runtime\\java-runtime-delta\\bin\\java.exe'
import { openSync } from 'node:fs'
const logFd = openSync('C:\\Users\\86130\\AppData\\Local\\Temp\\opencode\\client-launch.log', 'a')
const child = spawn(java, args, { cwd: versionDir, detached: true, stdio: ['ignore', logFd, logFd] })
child.unref()
console.log(`client_pid=${child.pid} libraries=${libraries.length}`)
