@echo off
rem ============================================================
rem TUI test client launcher (ASCII only: cmd parses this file
rem with the OEM codepage, so NO non-ASCII comments here!)
rem Usage: tools\tui.cmd [host] [wsPort] [httpPort]
rem Open two windows and match in both = cross-server battle
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0.."
mvn test-compile exec:java "-Dexec.mainClass=com.game.nt.tools.TuiTestClient" "-Dexec.classpathScope=test" "-Dexec.args=%*"
