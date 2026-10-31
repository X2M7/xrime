package com.osfans.trime.ime.keyboard;

import com.osfans.trime.ime.keyboard.SoundDemandCoordinator;
import java.util.*;
import java.util.concurrent.Executor;

public final class SoundDemandCoordinatorHarness {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static SoundDemandCoordinator.Catalog catalog(List<String> values){return new SoundDemandCoordinator.Catalog(values);}
    static final class Owner implements SoundDemandCoordinator.Owner {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>(), timers=new ArrayDeque<>(); long now;
        public void post(Runnable r){queue.add(r);}
        public void postDelayed(Runnable r,long ms){timers.add(r);}
        public void cancelDelayed(Runnable r){timers.remove(r);}
        public long uptimeMillis(){return now;}
        public boolean isOwner(){return true;}
        void drain(){while(!queue.isEmpty())queue.remove().run();}
        void timeout(){now+=4000;while(!timers.isEmpty())timers.remove().run();drain();}
    }
    static final class Worker implements Executor {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();boolean running;
        public void execute(Runnable r){check(queue.isEmpty(),"more than one queued task");queue.add(r);}
        void run(){check(!queue.isEmpty(),"missing worker task");running=true;try{queue.remove().run();}finally{running=false;}}
    }
    static final class Backend implements SoundDemandCoordinator.Backend<Object> {
        final Worker worker; SoundDemandCoordinator.Completion<Object> callback;
        Object lastPool; int nextId=1, created,loaded,unloaded,released,warnings,plays,fallbacks; boolean early=true,failRelease,failPlay,failUnload,failLoad;
        final Set<Object> live=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        final List<String> loadedRoots=new ArrayList<>();
        SoundDemandCoordinator.Failure lastFailure; boolean lastVisible;
        Backend(Worker w){worker=w;}
        public Object create(SoundDemandCoordinator.Completion<Object> c){check(worker.running,"create blocked owner");callback=c;created++;lastPool=new Object();live.add(lastPool);return lastPool;}
        public int load(Object p,SoundDemandCoordinator.Catalog catalog,String f){check(worker.running,"load blocked owner");loadedRoots.add(catalog.root);loaded++;if(failLoad)return 0;int id=nextId++;if(early)callback.loaded(p,id,true);return id;}
        public boolean play(Object p,int id,float gain,long requestedAt){check(worker.running && live.contains(p),"play blocked owner or pool released");if(failPlay)throw new IllegalStateException("injected play");plays++;return true;}
        public void fallback(int keyCode,float volume,long requestedAt){check(worker.running,"fallback blocked owner");fallbacks++;}
        public void unload(Object p,int id){check(worker.running,"unload blocked owner");if(failUnload)throw new IllegalStateException("injected unload");unloaded++;}
        public void release(Object p){check(worker.running,"release blocked owner");released++;if(failRelease)throw new IllegalStateException("injected release");check(live.remove(p),"double release");}
        public void failed(SoundDemandCoordinator.Failure reason,boolean visible){warnings++;lastFailure=reason;lastVisible=visible;}
    }
    static void flush(Owner owner,Worker worker){int steps=0;while(!worker.queue.isEmpty() || !owner.queue.isEmpty()){if(!worker.queue.isEmpty())worker.run();owner.drain();check(++steps<1000,"cycle");}}
    public static void main(String[] args){
        Owner owner=new Owner();Worker worker=new Worker();Backend backend=new Backend(worker);
        SoundDemandCoordinator<Object> c=new SoundDemandCoordinator<>(owner,worker,backend);
        c.configure(catalog(Arrays.asList("a","b")),false,c.revision());check(worker.queue.isEmpty(),"disabled configure loaded");
        check(c.press(0,1f,29,-1f)==SoundDemandCoordinator.Press.DISABLED,"disabled fallback");
        c.configure(catalog(Arrays.asList("a","b","a","c")),true,c.revision());flush(owner,worker);
        check(c.cachedCount()==3 && backend.loaded==3,"four distinct warmup cap");
        check(c.press(1,1f,29,-1f)==SoundDemandCoordinator.Press.QUEUED,"ready play");
        List<String> many=new ArrayList<>();for(int i=0;i<70;i++)many.add("s"+i);
        c.configure(catalog(many),true,c.revision());flush(owner,worker);
        for(int i=4;i<70;i++){check(c.press(i,1f,29,-1f)==SoundDemandCoordinator.Press.FALLBACK,"miss fallback");flush(owner,worker);}
        check(c.cachedCount()==64 && backend.unloaded>=6,"LRU cap/unload");
        int releases=backend.released;c.configure(catalog(Collections.singletonList("old")),true,c.revision());
        check(!worker.queue.isEmpty(),"pool release must be on worker");
        flush(owner,worker);check(backend.released>releases,"pool released");
        c.configure(new SoundDemandCoordinator.Catalog("old-root","old-folder",Collections.singletonList("blocked")),true,c.revision());
        // Execute release and create; leave the load itself in the worker queue.
        while(!worker.queue.isEmpty() && backend.created<4){worker.run();owner.drain();}
        check(!worker.queue.isEmpty(),"expected in-flight old work");
        c.configure(new SoundDemandCoordinator.Catalog("new-root","new-folder",Collections.singletonList("new")),true,c.revision());
        flush(owner,worker);
        check(!backend.loadedRoots.contains("old-root") && backend.loadedRoots.contains("new-root"),"superseded queued task loaded or new root lost");
        check(c.press(0,1f,29,-1f)==SoundDemandCoordinator.Press.QUEUED,"new generation not installed");
        c.configure(catalog(Collections.singletonList("wait")),true,c.revision());flush(owner,worker);
        c.close();flush(owner,worker);check(c.cachedCount()==0 && c.press(0,1f,29,-1f)==SoundDemandCoordinator.Press.DISABLED,"close");
        Owner lateOwner=new Owner();Worker lateWorker=new Worker();Backend late=new Backend(lateWorker);late.early=false;
        SoundDemandCoordinator<Object> timeout=new SoundDemandCoordinator<>(lateOwner,lateWorker,late);
        timeout.configure(catalog(Collections.singletonList("missing-callback")),true,timeout.revision());flush(lateOwner,lateWorker);
        check(timeout.cachedCount()==0 && !lateOwner.timers.isEmpty(),"timeout armed");
        // A normal late callback removes its timeout rather than accumulating owner tasks.
        late.callback.loaded(late.lastPool,1,true);lateOwner.drain();
        check(lateOwner.timers.isEmpty() && timeout.cachedCount()==1,"late callback left timer behind");
        timeout.configure(catalog(Collections.singletonList("second-no-callback")),true,timeout.revision());flush(lateOwner,lateWorker);
        lateOwner.timeout();flush(lateOwner,lateWorker);
        check(late.unloaded==1 && timeout.cachedCount()==0,"timeout sample not unloaded");
        Owner gateOwner=new Owner();Worker gateWorker=new Worker();Backend gateBackend=new Backend(gateWorker);
        SoundDemandCoordinator<Object> gated=new SoundDemandCoordinator<>(gateOwner,gateWorker,gateBackend);
        gated.configure(catalog(Collections.singletonList("a")),true,gated.revision());flush(gateOwner,gateWorker);
        int before=gateBackend.plays;gated.press(0,1f,29,-1f);gated.close();flush(gateOwner,gateWorker);
        check(gateBackend.plays==before,"play claimed after close");
        gated.configure(catalog(Collections.singletonList("a")),true,gated.revision());flush(gateOwner,gateWorker);
        int preRevoke=gateBackend.plays;gated.press(0,1f,29,-1f);gated.revokeNow();flush(gateOwner,gateWorker);
        check(gateBackend.plays==preRevoke,"off-owner revoke allowed an unclaimed play");
        gated.configure(catalog(Collections.singletonList("a")),true,gated.revision());flush(gateOwner,gateWorker);
        before=gateBackend.plays;gated.press(0,1f,29,-1f);gateOwner.now+=201;flush(gateOwner,gateWorker);
        check(gateBackend.plays==before,"expired play was not dropped");
        gated.press(0,1f,29,-1f);
        for(int i=0;i<10000;i++)gated.press(0,1f,29,-1f);
        flush(gateOwner,gateWorker);
        check(gateBackend.plays-before<=17,"unbounded play queue");
        Owner stoppedOwner=new Owner();Worker stoppedWorker=new Worker();Backend stoppedBackend=new Backend(stoppedWorker);
        SoundDemandCoordinator<Object> stopped=new SoundDemandCoordinator<>(stoppedOwner,stoppedWorker,stoppedBackend);
        stopped.configure(catalog(Collections.singletonList("a")),true,stopped.revision());stopped.revokeNow();flush(stoppedOwner,stoppedWorker);
        check(stoppedBackend.created==0 && stoppedBackend.loaded==0,"revoked queued create started");
        // A delayed preference update cannot undo a newer off-thread revocation.
        long staleRevision=gated.revision();long newerRevision=gated.revokeNow();
        check(!gated.configure(catalog(Collections.singletonList("stale")),true,staleRevision),"stale preference reenabled audio");
        check(!gated.resumeInput(staleRevision),"stale preference resumed audio");
        check(gated.resumeInput(newerRevision),"current editor resume rejected");
        check(gated.press(0,1f,29,-1f)==SoundDemandCoordinator.Press.DISABLED,"editor resume undid preference revoke");
        check(gated.configure(catalog(Collections.singletonList("a")),true,newerRevision),"current preference rejected");flush(gateOwner,gateWorker);
        int poolsBeforeBoundary=gateBackend.created, cacheBeforeBoundary=gated.cachedCount();
        int playsBeforeBoundary=gateBackend.plays;gated.press(0,1f,29,-1f);gated.pauseInput();flush(gateOwner,gateWorker);
        check(gateBackend.plays==playsBeforeBoundary,"old editor play after boundary");
        check(gated.press(0,1f,29,-1f)==SoundDemandCoordinator.Press.DISABLED,"paused editor played");
        check(gated.resumeInput(gated.revision()),"current editor resume failed");
        check(gated.cachedCount()==cacheBeforeBoundary && gateBackend.created==poolsBeforeBoundary,"editor boundary retired pool");
        check(gated.press(0,1f,29,-1f)==SoundDemandCoordinator.Press.QUEUED,"new editor lost cache");flush(gateOwner,gateWorker);
        check(gateBackend.plays==playsBeforeBoundary+1,"new editor audio missing");
        Owner releaseOwner=new Owner();Worker releaseWorker=new Worker();Backend releaseBackend=new Backend(releaseWorker);
        SoundDemandCoordinator<Object> guarded=new SoundDemandCoordinator<>(releaseOwner,releaseWorker,releaseBackend);
        guarded.configure(catalog(Collections.singletonList("a")),true,guarded.revision());flush(releaseOwner,releaseWorker);
        releaseBackend.failRelease=true;
        guarded.configure(catalog(Collections.singletonList("b")),true,guarded.revision());flush(releaseOwner,releaseWorker);
        check(releaseBackend.live.size()==1 && releaseBackend.created==1,"release failure created a new pool");
        guarded.press(0,1f,29,-1f);flush(releaseOwner,releaseWorker);
        check(releaseBackend.fallbacks==1 && releaseBackend.created==1,"fallback unavailable while release retry cooling down");
        releaseBackend.failRelease=false;releaseOwner.now+=1000;guarded.press(0,1f,29,-1f);flush(releaseOwner,releaseWorker);
        check(releaseBackend.live.size()==1 && releaseBackend.created==2,"release retry did not recover");
        Owner cooldownOwner=new Owner();Worker cooldownWorker=new Worker();Backend cooldownBackend=new Backend(cooldownWorker);
        SoundDemandCoordinator<Object> cooldown=new SoundDemandCoordinator<>(cooldownOwner,cooldownWorker,cooldownBackend);
        cooldownBackend.failLoad=true;cooldown.configure(catalog(Collections.singletonList("bad")),true,cooldown.revision());flush(cooldownOwner,cooldownWorker);
        check(cooldownBackend.loaded==1 && cooldownBackend.warnings==1 && cooldownBackend.lastFailure==SoundDemandCoordinator.Failure.SAMPLE_LOAD && cooldownBackend.lastVisible,"load failure not reported once");
        for(int i=0;i<100;i++)cooldown.press(0,1f,29,-1f);flush(cooldownOwner,cooldownWorker);
        check(cooldownBackend.loaded==1 && cooldownBackend.warnings==1,"failed sample retried during cooldown");
        cooldownBackend.failLoad=false;cooldownOwner.now+=1000;cooldown.press(0,1f,29,-1f);flush(cooldownOwner,cooldownWorker);
        check(cooldown.isCached(0),"failed sample did not retry after cooldown");cooldown.close();flush(cooldownOwner,cooldownWorker);
        Owner failureOwner=new Owner();Worker failureWorker=new Worker();Backend failureBackend=new Backend(failureWorker);
        SoundDemandCoordinator<Object> failure=new SoundDemandCoordinator<>(failureOwner,failureWorker,failureBackend);
        failure.configure(catalog(many),true,failure.revision());flush(failureOwner,failureWorker);
        failureBackend.failPlay=true;failure.press(0,1f,29,-1f);flush(failureOwner,failureWorker);
        failureBackend.failPlay=false;failure.press(0,1f,29,-1f);flush(failureOwner,failureWorker);
        check(failure.isCached(0) && failureBackend.created==2,"play failure prevented subsequent load");
        for(int i=0;i<64;i++){failure.press(i,1f,29,-1f);flush(failureOwner,failureWorker);}
        failureBackend.failUnload=true;failure.press(64,1f,29,-1f);flush(failureOwner,failureWorker);
        failureBackend.failUnload=false;failure.press(64,1f,29,-1f);flush(failureOwner,failureWorker);
        check(failure.isCached(64) && failureBackend.created==3,"unload failure prevented subsequent load");
        failure.close();flush(failureOwner,failureWorker);
        Owner fuzzOwner=new Owner();Worker fuzzWorker=new Worker();Backend fuzzBackend=new Backend(fuzzWorker);
        SoundDemandCoordinator<Object> fuzz=new SoundDemandCoordinator<>(fuzzOwner,fuzzWorker,fuzzBackend);
        Random random=new Random(82593);
        for(int i=0;i<20000;i++){
            int action=random.nextInt(6);
            if(action==0)fuzz.configure(catalog(many),true,fuzz.revision());
            else if(action==1)fuzz.configure(catalog(many),false,fuzz.revision());
            else if(action==2)fuzz.close();
            else fuzz.press(random.nextInt(70),1f,29,-1f);
            if(!fuzzWorker.queue.isEmpty() && random.nextBoolean())fuzzWorker.run();
            fuzzOwner.drain();
            check(fuzz.cachedCount()<=64 && fuzzBackend.live.size()<=1,"resource bound");
        }
        fuzz.close();flush(fuzzOwner,fuzzWorker);check(fuzzBackend.live.isEmpty(),"pools leaked after close");
        System.out.println("PASS worker create/load/unload/release, early callback, 70-to-64 LRU, switch/close, timeout");
    }
}
