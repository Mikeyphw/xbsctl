package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	xbsmodule "github.com/Mikeyphw/xbsctl/internal/module"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 || args[0] == "--help" || args[0] == "-h" {
		usage()
		return 0
	}
	root, err := xbsmodule.FindAppRoot(".")
	if err != nil {
		fmt.Fprintf(os.Stderr, "xbsrepo: %v\n", err)
		return 1
	}
	switch args[0] {
	case "build-runtime":
		fs := flag.NewFlagSet("build-runtime", flag.ContinueOnError)
		out := fs.String("out", filepath.Join(root, ".tmp", "xbs-android-arm64"), "output xbs runtime path")
		all := fs.Bool("all", false, "build xbs, XBSctl, and XtremeBSd into the output directory")
		goos := fs.String("goos", "android", "target GOOS")
		goarch := fs.String("goarch", "arm64", "target GOARCH")
		if fs.Parse(args[1:]) != nil {
			return 2
		}
		if *all {
			outDir := *out
			if filepath.Base(outDir) == "xbs-android-arm64" {
				outDir = filepath.Dir(outDir)
			}
			outputs, err := xbsmodule.BuildRuntimeSet(root, outDir, *goos, *goarch)
			if err != nil {
				fmt.Fprintf(os.Stderr, "xbsrepo build-runtime: %v\n", err)
				return 1
			}
			fmt.Println(outputs["xbs"])
			fmt.Println(outputs["XBSctl"])
			fmt.Println(outputs["XtremeBSd"])
			return 0
		}
		if err := xbsmodule.BuildRuntime(root, *out, *goos, *goarch); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo build-runtime: %v\n", err)
			return 1
		}
		fmt.Println(*out)
		return 0
	case "build-module":
		fs := flag.NewFlagSet("build-module", flag.ContinueOnError)
		runtime := fs.String("runtime", "", "prebuilt runtime binary; defaults to a freshly built Android/arm64 binary")
		out := fs.String("out", filepath.Join(root, "dist", "XtremeBS-go.zip"), "output module zip")
		stage := fs.String("stage", filepath.Join(root, ".tmp", "module-stage"), "staging directory")
		install := fs.Bool("install", false, "install the resulting module with Magisk or KernelSU")
		if fs.Parse(args[1:]) != nil {
			return 2
		}
		// R04: never build or stage a candidate as part of an implicit root install request.
		if *install {
			fmt.Fprintln(os.Stderr, "R04 refuses --install; use install-command for an independently verified candidate")
			return 2
		}
		runtimePath := *runtime
		if runtimePath == "" {
			runtimeDir := filepath.Join(root, ".tmp", "xbs-android-arm64-set")
			runtimes, err := xbsmodule.BuildRuntimeSet(root, runtimeDir, "android", "arm64")
			if err != nil {
				fmt.Fprintf(os.Stderr, "xbsrepo build-module runtime: %v\n", err)
				return 1
			}
			runtimePath = runtimes["xbs"]
		}
		if err := xbsmodule.StageModule(root, runtimePath, *stage); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo build-module stage: %v\n", err)
			return 1
		}
		if err := xbsmodule.ValidateModule(*stage); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo build-module validate: %v\n", err)
			return 1
		}
		if err := xbsmodule.Zip(*stage, *out); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo build-module zip: %v\n", err)
			return 1
		}
		fmt.Println(*out)
		if *install {
			if err := xbsmodule.Install(*out); err != nil {
				fmt.Fprintf(os.Stderr, "xbsrepo install-module: %v\n", err)
				return 1
			}
		}
		return 0
	case "verify-module-zip":
        fs := flag.NewFlagSet("verify-module-zip",flag.ContinueOnError)
        zipPath := fs.String("zip","","module ZIP path")
        sha := fs.String("sha256","","expected exact ZIP SHA256")
        if fs.Parse(args[1:])!=nil{return 2}
        if *zipPath=="" || *sha=="" {fmt.Fprintln(os.Stderr,"R04 requires exact --zip and --sha256");return 2}
        if err:=xbsmodule.VerifyModuleZip(*zipPath);err!=nil{fmt.Fprintln(os.Stderr,"R04 verify:",err);return 1}
        if err:=xbsmodule.VerifyR04ZipHash(*zipPath,*sha);err!=nil{fmt.Fprintln(os.Stderr,"R04 digest:",err);return 1}
        fmt.Println("R04 module ZIP validated; digest agrees (NOT a CI/device acceptance)")
        return 0
    case "install-command":
        fs:=flag.NewFlagSet("install-command",flag.ContinueOnError)
        zipPath:=fs.String("zip","","absolute module ZIP")
        sha:=fs.String("sha256","","exact ZIP hash")
        commit:=fs.String("commit","","exact source SHA")
        run:=fs.Int64("run-id",0,"hosted run identifier")
        attempt:=fs.Int("run-attempt",0,"hosted run attempt")
        artifact:=fs.String("artifact","","hosted artifact name")
        manager:=fs.String("manager","","explicit kernelsu or magisk installer selection")
        if fs.Parse(args[1:])!=nil{return 2}
        candidate:=xbsmodule.R04InstallCandidate{Repository:"Mikeyphw/xbsctl",Commit:*commit,RunID:*run,RunAttempt:*attempt,Artifact:*artifact,SHA256:*sha,ZipPath:*zipPath,Manager:*manager}
        cmd,err:=xbsmodule.QualifiedInstallCommand(candidate)
        if err!=nil{fmt.Fprintln(os.Stderr,"R04 install candidate refused:",err);return 1}
        fmt.Println("R04 operator-reviewed candidate only; verify actual hosted job and artifact association independently:")
        fmt.Println(cmd)
        return 0
    case "rollback-stage":
        fs:=flag.NewFlagSet("rollback-stage",flag.ContinueOnError)
        stage:=fs.String("stage",filepath.Join(root,".tmp","module-stage"),"offline stage directory")
        if fs.Parse(args[1:])!=nil{return 2}
        if err:=xbsmodule.RollbackR04Stage(*stage);err!=nil{fmt.Fprintln(os.Stderr,"R04 rollback refused:",err);return 1}
        fmt.Println("R04 staged rollback recorded (installed Android module unchanged)");return 0
    case "finalize-stage":
        fs:=flag.NewFlagSet("finalize-stage",flag.ContinueOnError)
        stage:=fs.String("stage",filepath.Join(root,".tmp","module-stage"),"offline stage directory")
        ack:=fs.String("ack","","explicit acceptance acknowledgement")
        if fs.Parse(args[1:])!=nil{return 2}
        if err:=xbsmodule.FinalizeR04Stage(*stage,*ack);err!=nil{fmt.Fprintln(os.Stderr,"R04 finalize refused:",err);return 1}
        fmt.Println("R04 previous staged backup explicitly finalized");return 0
    case "uninstall-plan":
        fs:=flag.NewFlagSet("uninstall-plan",flag.ContinueOnError)
        stage:=fs.String("stage",filepath.Join(root,".tmp","module-stage"),"offline stage directory")
        if fs.Parse(args[1:])!=nil{return 2}
        receipt,err:=xbsmodule.PlanR04Uninstall(*stage)
        if err!=nil{fmt.Fprintln(os.Stderr,"R04 uninstall plan refused:",err);return 1}
        json.NewEncoder(os.Stdout).Encode(receipt);return 0
    case "install-module":
		fs := flag.NewFlagSet("install-module", flag.ContinueOnError)
		zipPath := fs.String("zip", filepath.Join(root, "dist", "XtremeBS-go.zip"), "module zip to install")
		if fs.Parse(args[1:]) != nil {
			return 2
		}
		if err := xbsmodule.Install(*zipPath); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo install-module: %v\n", err)
			return 1
		}
		return 0
	case "validate":
		fs := flag.NewFlagSet("validate", flag.ContinueOnError)
		stage := fs.String("stage", filepath.Join(root, ".tmp", "module-stage"), "module tree to validate")
		if fs.Parse(args[1:]) != nil {
			return 2
		}
		if err := xbsmodule.ValidateModule(*stage); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo validate: %v\n", err)
			return 1
		}
		fmt.Println("XBS module validation passed")
		return 0
	case "final-seal":
		if err := xbsmodule.FinalSeal(root); err != nil {
			fmt.Fprintf(os.Stderr, "xbsrepo final-seal: %v\n", err)
			return 1
		}
		fmt.Println("XBS XGO13 final seal passed")
		return 0
	default:
		fmt.Fprintf(os.Stderr, "xbsrepo: unknown command %q\n", args[0])
		usage()
		return 2
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `xbsrepo manages the XBS Go-native module staging path.

Usage:
  xbsrepo build-runtime [--out PATH] [--all]
  xbsrepo build-module [--runtime PATH] [--out ZIP] [--stage DIR] [--install]
  xbsrepo install-module [--zip ZIP]
  xbsrepo validate [--stage DIR]
  xbsrepo final-seal
  xbsrepo verify-module-zip --zip ZIP --sha256 HASH
  xbsrepo install-command --zip ZIP --sha256 HASH --commit SHA --run-id ID --run-attempt N --artifact NAME --manager kernelsu|magisk
  xbsrepo rollback-stage [--stage DIR]
  xbsrepo finalize-stage [--stage DIR] --ack I_ACCEPT_VERIFIED_STAGE
  xbsrepo uninstall-plan [--stage DIR]`)
}
