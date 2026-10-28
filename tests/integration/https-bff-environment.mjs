// Tools receive only OS executable/home/temp locations. In particular, Spring,
// libpq, JVM options, proxies and dynamic loader configuration are not inherited.
const allowed = ['PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'TMP', 'TEMP', 'SystemRoot',
  'COMSPEC', 'WINDIR', 'USERPROFILE', 'SYSTEMDRIVE'];

export function minimalEnvironment(source = process.env) {
  return Object.fromEntries(allowed.filter((key) => source[key] !== undefined)
    .map((key) => [key, source[key]]));
}
