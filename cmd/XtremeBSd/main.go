package main

import (
	"os"

	"github.com/Mikeyphw/xbsctl/internal/daemon"
)

func main() {
	os.Exit(daemon.Main(os.Args[1:], os.Stdout, os.Stderr))
}
