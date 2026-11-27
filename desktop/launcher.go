// OtoTag.exe - tiny Windows launcher: starts the bundled JRE's javaw
// with OtoTag.jar from the same folder, no console window.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
)

func main() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	dir := filepath.Dir(exe)
	javaw := filepath.Join(dir, "jre", "bin", "javaw.exe")
	jar := filepath.Join(dir, "OtoTag.jar")
	args := []string{"-Dfile.encoding=UTF-8"}
	// Class-data sharing: the first launch records a shared archive
	// next to the jar, and later launches start from it (faster boot).
	// The archive name carries the jar size, so an updated jar never
	// reuses a stale archive; old archives are swept here. -Xshare:auto
	// makes the JVM fall back silently if an archive is unusable.
	if info, err := os.Stat(jar); err == nil {
		jsa := filepath.Join(dir, "ototag-"+strconv.FormatInt(info.Size(), 10)+".jsa")
		if old, _ := filepath.Glob(filepath.Join(dir, "ototag-*.jsa")); old != nil {
			for _, f := range old {
				if f != jsa {
					_ = os.Remove(f)
				}
			}
		}
		if _, err := os.Stat(jsa); err == nil {
			args = append(args, "-Xshare:auto", "-XX:SharedArchiveFile="+jsa)
		} else {
			args = append(args, "-XX:ArchiveClassesAtExit="+jsa)
		}
	}
	args = append(args, "-jar", jar)
	cmd := exec.Command(javaw, args...)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	_ = cmd.Start()
}
