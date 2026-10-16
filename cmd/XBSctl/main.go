package main

import (
	"os"

	"github.com/Mikeyphw/xbsctl/internal/control"
)

func main() {
	os.Exit(control.Main(os.Args[1:], os.Stdout, os.Stderr))
}
