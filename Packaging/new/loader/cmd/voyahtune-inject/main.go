package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"voyahtune/unified-loader/injecthelper"
)

func main() {
	requestPath := flag.String("request", "", "Root-owned helper request")
	receiptPath := flag.String("receipt", "", "Correlated helper receipt")
	flag.Parse()
	if *requestPath == "" || *receiptPath == "" || flag.NArg() != 0 || os.Geteuid() != 0 {
		fmt.Fprintln(os.Stderr, "root, --request and --receipt are required")
		os.Exit(2)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	if err := watchParent(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	request, err := injecthelper.ReadRequest(*requestPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	receipt, err := injecthelper.Run(ctx, request, *receiptPath)
	json.NewEncoder(os.Stdout).Encode(receipt)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
