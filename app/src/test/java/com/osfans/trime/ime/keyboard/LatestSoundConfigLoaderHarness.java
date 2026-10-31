package com.osfans.trime.ime.keyboard;

import com.osfans.trime.ime.keyboard.LatestSoundConfigLoader;
import java.util.*;
import java.util.concurrent.*;
public final class LatestSoundConfigLoaderHarness {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static final class Owner implements LatestSoundConfigLoader.Owner {final ArrayDeque<Runnable> q=new ArrayDeque<>();public boolean isOwner(){return true;} public void post(Runnable r){q.add(r);}void drain(){while(!q.isEmpty())q.remove().run();}}
 static final class Worker implements Executor {final ArrayDeque<Runnable> q=new ArrayDeque<>();boolean reject; public void execute(Runnable r){if(reject)throw new RejectedExecutionException();check(q.isEmpty(),"unbounded worker tasks");q.add(r);}void run(){q.remove().run();}}
 public static void main(String[] args){
  Owner owner=new Owner();Worker worker=new Worker();List<Integer> reads=new ArrayList<>(),published=new ArrayList<>();
  LatestSoundConfigLoader<Integer,Integer> c=new LatestSoundConfigLoader<>(owner,worker,x->{reads.add(x);return x;},(x,result,error)->{check(error==null && x.equals(result),"bad result");published.add(result);});
  c.request(0);for(int i=1;i<=10000;i++)c.request(i);check(worker.q.size()==1,"more than one task");
  worker.run();owner.drain();check(published.isEmpty(),"stale config published");check(worker.q.size()==1,"latest request missing");
  worker.run();owner.drain();check(reads.equals(Arrays.asList(0,10000)) && published.equals(Collections.singletonList(10000)),"latest request not used");
  c.request(10001);c.request(10002);c.invalidate();c.request(10003);worker.run();owner.drain();
  check(worker.q.size()==1 && published.size()==1,"invalidate accepted old read or lost new request");worker.run();owner.drain();
  check(published.equals(Arrays.asList(10000,10003)),"reused loader published stale result");
  c.request(10004);c.request(10005);c.close();worker.run();owner.drain();check(worker.q.isEmpty() && published.size()==2,"closed published late result");
  Owner cancelOwner=new Owner();Worker cancelWorker=new Worker();List<String> events=new ArrayList<>();
  LatestSoundConfigLoader<String,String> cancel=new LatestSoundConfigLoader<>(cancelOwner,cancelWorker,x->{if(x.equals("cancel"))throw new CancellationException();if(x.equals("fatal"))throw new AssertionError();if(x.equals("fail"))throw new java.io.IOException();return x;},(x,v,e)->events.add(x+":"+(e==null?"ok":"failed")));
  cancel.request("cancel");try{cancelWorker.run();throw new AssertionError("cancel swallowed");}catch(CancellationException expected){}cancelOwner.drain();check(events.isEmpty(),"cancel published");
  cancel.request("fatal");try{cancelWorker.run();throw new AssertionError("fatal swallowed");}catch(AssertionError expected){}cancelOwner.drain();check(events.isEmpty(),"fatal published");
  cancel.request("fail");cancelWorker.run();cancelOwner.drain();check(events.equals(Collections.singletonList("fail:failed")),"ordinary failure lost");
  cancelWorker.reject=true;cancel.request("reject");check(events.contains("reject:failed"),"rejection lost");cancelWorker.reject=false;cancel.request("ok");cancelWorker.run();cancelOwner.drain();check(events.contains("ok:ok"),"rejection blocked retry");
  System.out.println("PASS bounded single config worker, 10000 latest-only, stale/close/cancel/fatal/rejection");
 }
}
