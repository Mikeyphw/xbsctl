package control

import "testing"

func TestR05aControllerObservationMatching(t *testing.T) {
  old:=StatusSnapshot{Available:true,Mode:"normal",LastReloadTime:"older",ActiveEvents:[]string{"boot"}}
  applied:=StatusSnapshot{Available:true,Mode:"normal",LastReloadTime:"later",ActiveEvents:[]string{"boot","manual"}}
  cases:=[]struct{action,event string; after StatusSnapshot; want bool}{
   {"start","manual",applied,true}, {"stop","manual",applied,false}, {"stop","charging",applied,true},
   {"pause","",StatusSnapshot{Available:true,Mode:"paused"},true},
   {"pause","",old,false}, {"resume","",applied,true}, {"safe","",StatusSnapshot{Available:true,Mode:"safe"},true},
   {"reload","",applied,true}, {"reload","",old,false},
   {"reload","",StatusSnapshot{Available:true,LastReloadTime:""},false},
   {"start","manual",StatusSnapshot{Available:false,ActiveEvents:[]string{"manual"}},false},
   {"reset","",applied,false},
  }
  for _,c:=range cases{if got:=confirmedObservation(c.action,c.event,old,c.after);got!=c.want{t.Errorf("%s %s: got %v want %v",c.action,c.event,got,c.want)}}
}
