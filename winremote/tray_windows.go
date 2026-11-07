package main

// The notification-area icon: left click opens the page, the menu has "Start with Windows" and "Quit".

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"math"
	"os"

	"fyne.io/systray"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	runKeyPath = `Software\Microsoft\Windows\CurrentVersion\Run`
	runValue   = "PCDashRemote"
)

// trayIcon draws a 32x32 power symbol on a blue disc and wraps the PNG in an ICO container.
func trayIcon() []byte {
	const n = 32
	img := image.NewNRGBA(image.Rect(0, 0, n, n))
	bg := color.NRGBA{0x1f, 0x6f, 0xeb, 0xff}
	fg := color.NRGBA{0xff, 0xff, 0xff, 0xff}
	c := float64(n-1) / 2
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			dx, dy := float64(x)-c, float64(y)-c
			r := math.Hypot(dx, dy)
			if r > 15.5 {
				continue
			}
			px := bg
			// ring with a gap at the top, and a bar through the gap
			ang := math.Atan2(dx, -dy) // 0 at the top, clockwise
			ring := r >= 7.5 && r <= 10 && math.Abs(ang) > 0.55
			bar := math.Abs(dx) <= 1.4 && dy >= -11 && dy <= -1
			if ring || bar {
				px = fg
			}
			img.SetNRGBA(x, y, px)
		}
	}
	var pngBuf bytes.Buffer
	_ = png.Encode(&pngBuf, img)
	var ico bytes.Buffer
	w := func(v any) { _ = binary.Write(&ico, binary.LittleEndian, v) }
	w(uint16(0)) // reserved
	w(uint16(1)) // type: icon
	w(uint16(1)) // one image
	ico.Write([]byte{n, n, 0, 0})
	w(uint16(1))  // planes
	w(uint16(32)) // bits per pixel
	w(uint32(pngBuf.Len()))
	w(uint32(22)) // offset of the image data
	ico.Write(pngBuf.Bytes())
	return ico.Bytes()
}

func autostartEnabled() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue(runValue)
	return err == nil
}

func setAutostart(on bool) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if !on {
		err := k.DeleteValue(runValue)
		if err == registry.ErrNotExist {
			return nil
		}
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return k.SetStringValue(runValue, `"`+exe+`" -no-browser`)
}

// runTray shows the icon and blocks until Quit; open is called for a click, quit once when the app ends.
func runTray(tooltip string, open func(), quit func()) {
	systray.Run(func() {
		systray.SetIcon(trayIcon())
		systray.SetTooltip(tooltip)
		systray.SetOnTapped(open)
		mOpen := systray.AddMenuItem("Open PCDash Remote", "")
		mAuto := systray.AddMenuItemCheckbox("Start with Windows", "", autostartEnabled())
		systray.AddSeparator()
		mQuit := systray.AddMenuItem("Quit", "")
		go func() {
			for {
				select {
				case <-mOpen.ClickedCh:
					open()
				case <-mAuto.ClickedCh:
					want := !mAuto.Checked()
					if err := setAutostart(want); err != nil {
						continue
					}
					if want {
						mAuto.Check()
					} else {
						mAuto.Uncheck()
					}
				case <-mQuit.ClickedCh:
					systray.Quit()
					return
				}
			}
		}()
	}, quit)
}

// fatalBox tells the user why the app cannot start; there is no console when it is built as a GUI program.
func fatalBox(msg string) {
	title, _ := windows.UTF16PtrFromString("PCDash Remote")
	text, _ := windows.UTF16PtrFromString(msg)
	_, _ = windows.MessageBox(0, text, title, windows.MB_OK|windows.MB_ICONERROR)
}
