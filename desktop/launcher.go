// OtoTag.exe - tiny Windows launcher: starts the bundled JRE's javaw
// with OtoTag.jar from the same folder, no console window.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
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
	cmd := exec.Command(javaw, "-Dfile.encoding=UTF-8", "-jar", jar)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	_ = cmd.Start()
}
