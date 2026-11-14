package control

import (
    "path/filepath"
    "strings"
    "sync"
    "testing"
)

func FuzzR09ARequestID(f *testing.F) {
    for _, seed := range []string{"123-456", "0-0", "", "../escape", "100/500", "10.json", "123\n456", "a-b", strings.Repeat("9", 97)} { f.Add(seed) }
    f.Fuzz(func(t *testing.T, id string) {
        if !validRequestID(id) { return }
        if len(id)<3 || len(id)>96 || strings.ContainsAny(id, "/.\\\x00\n\r\t") { t.Fatalf("unsafe request id: %q", id) }
        for _, r := range id { if r!='-' && (r<'0' || r>'9') { t.Fatalf("unsafe rune accepted: %q", id) } }
    })
}

// Tests the real atomic queue in an isolated temp directory with concurrent
// producers; the consumer must see each durable request ID exactly once.
func TestR09AConcurrentQueueIDs(t *testing.T) {
    ctl := filepath.Join(t.TempDir(), "control")
    const count=8
    ids := make(chan string, count)
    errors := make(chan error, count)
    var wg sync.WaitGroup
    for i:=0; i<count; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            req, err := QueueRequest(ctl,"start","manual")
            if err!=nil { errors<-err;return }
            ids<-req.ID
        }()
    }
    wg.Wait(); close(errors);close(ids)
    for err := range errors { t.Fatalf("concurrent queue refused: %v",err) }
    unique := map[string]bool{}
    for id := range ids {
        if !validRequestID(id) || unique[id] { t.Fatalf("invalid or duplicate request ID: %q",id) }
        unique[id]=true
    }
    if len(unique)!=count { t.Fatalf("lost concurrent requests: %d/%d",len(unique),count) }
    for i:=0;i<count;i++ {
        req, path, err := NextRequest(ctl)
        if err!=nil || path=="" { t.Fatalf("dequeue %d: %q %v",i,path,err) }
        if !unique[req.ID] { t.Fatalf("unknown or duplicate dequeue: %q",req.ID) }
        delete(unique,req.ID)
        if err:=CompleteRequest(ctl,req,nil);err!=nil { t.Fatalf("receipt %d: %v",i,err) }
        ack,ok:=ReadAck(ctl,req.ID)
        if !ok || ack.State!="applied" { t.Fatalf("unobserved ack for %q: %+v",req.ID,ack) }
    }
    if len(unique)!=0 {t.Fatalf("undelivered requests remain: %d",len(unique))}
}
