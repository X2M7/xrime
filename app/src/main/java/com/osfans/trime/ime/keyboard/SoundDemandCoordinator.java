/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard;

import java.util.*;
import java.util.concurrent.Executor;

/** Isolated draft. Every state operation is owner-thread confined; native work uses one worker. */
public final class SoundDemandCoordinator<P> {
    public interface Owner {
        void post(Runnable task);
        void postDelayed(Runnable task, long millis);
        void cancelDelayed(Runnable task);
        long uptimeMillis();
        boolean isOwner();
    }
    public interface Completion<P> { void loaded(P pool, int id, boolean success); }
    public interface Backend<P> {
        P create(Completion<P> callback) throws Exception;
        int load(P pool, Catalog catalog, String file) throws Exception;
        boolean play(P pool, int id, float gain, long requestedAt);
        void fallback(int keyCode, float volume, long requestedAt);
        void unload(P pool, int id) throws Exception;
        void release(P pool) throws Exception;
        void failed(Failure reason, boolean visible);
    }
    public enum Failure { CONFIG_READ, CONFIG_INVALID, EXECUTOR_REJECTED, POOL_CREATE, POOL_RELEASE, POOL_PLAY, SYSTEM_PLAY, SAMPLE_LOAD, SAMPLE_UNLOAD, SAMPLE_DECODE, LOAD_TIMEOUT }
    public enum Press { QUEUED, FALLBACK, DISABLED }
    /** Construct off the owner thread; callers cannot mutate the coordinator's snapshot. */
    public static final class Catalog {
        public final String root, folder;
        private final List<String> files;
        public Catalog(String root,String folder,List<String> source){
            this.root=Objects.requireNonNull(root);this.folder=Objects.requireNonNull(folder);
            files=Collections.unmodifiableList(new ArrayList<>(source));
        }
        public Catalog(List<String> source){this("","",source);}
    }
    private enum Kind { CREATE, LOAD, UNLOAD, RELEASE, PLAY }
    private static final int CAPACITY=64, WARMUP=4, WARMUP_SCAN=16, MAX_EARLY=16, MAX_PLAYS=16;
    private static final long RETRY_MS=1000, CALLBACK_TIMEOUT_MS=4000, PLAY_TTL_MS=200;
    private final Owner owner;
    private final Executor worker;
    private final Backend<P> backend;
    private final LinkedHashMap<String,Integer> cache=new LinkedHashMap<>(64,.75f,true);
    private final LinkedHashMap<String,Long> retry=new LinkedHashMap<>(64,.75f,true);
    private final HashMap<Integer,Boolean> early=new HashMap<>();
    private final ArrayDeque<Play<P>> plays=new ArrayDeque<>();
    private final Object gate=new Object();
    private long gateGeneration, gateRevision, gateCatalogGeneration;
    private boolean gateEnabled, preferenceRevoked;
    private long playGeneration;
    private boolean inputActive = true;
    private List<String> files=Collections.emptyList();
    private Catalog catalog=new Catalog(Collections.emptyList());
    private String pending;
    private long generation;
    private int warmupCursor,warmed;
    private boolean enabled, warned;
    private P currentPool, pendingRelease;
    private Op<P> op;
    private long createRetryAfter, releaseRetryAfter;

    private static final class Op<P> {
        final Kind kind; final long generation; final P pool; final String path; final int id;
        Catalog catalog;
        boolean returned, retired; int sample;
        Runnable timeout;
        Play<P> play;
        Op(Kind kind,long generation,P pool,String path,int id){this.kind=kind;this.generation=generation;this.pool=pool;this.path=path;this.id=id;}
    }
    private static final class Play<P> {
        final long generation, created; final P pool; final int id, keyCode; final float gain, systemVolume;
        Play(long generation,long created,P pool,int id,int keyCode,float gain,float systemVolume){
            this.generation=generation;this.created=created;this.pool=pool;this.id=id;this.keyCode=keyCode;this.gain=gain;this.systemVolume=systemVolume;
        }
    }
    public SoundDemandCoordinator(Owner owner,Executor worker,Backend<P> backend){this.owner=Objects.requireNonNull(owner);this.worker=Objects.requireNonNull(worker);this.backend=Objects.requireNonNull(backend);}
    private void checkOwner(){if(!owner.isOwner())throw new IllegalStateException("owner thread required");}
    /** Only the latest preference revocation may install a new configuration. */
    public boolean configure(Catalog selected,boolean on,long expectedRevision){
        checkOwner();
        synchronized(gate){
            if(gateRevision!=expectedRevision)return false;
            generation++;playGeneration++;enabled=on;plays.clear();
            gateGeneration=playGeneration;gateCatalogGeneration=generation;preferenceRevoked=false;gateEnabled=on && inputActive;
        }
        pending=null;warmupCursor=warmed=0;warned=false;retry.clear();createRetryAfter=0;
        retirePool();catalog=selected;files=selected.files;pump();return true;
    }
    public Press press(int index,float gain,int keyCode,float systemVolume){
        checkOwner();if(!enabled || !inputActive || isPreferenceRevoked())return Press.DISABLED;
        if(index<0 || index>=files.size()){enqueue(null,0,keyCode,gain,systemVolume);pump();return Press.FALLBACK;}
        String path=files.get(index);Integer id=cache.get(path);
        if(id!=null && currentPool!=null){
            enqueue(currentPool,id,keyCode,gain,systemVolume);pump();return Press.QUEUED;
        }
        enqueue(null,0,keyCode,gain,systemVolume);
        if(op==null || op.kind!=Kind.LOAD || op.generation!=generation || !path.equals(op.path))pending=path;else pending=null;
        pump();return Press.FALLBACK;
    }
    public void close(){checkOwner();generation++;playGeneration++;enabled=false;inputActive=false;warned=false;plays.clear();synchronized(gate){gateRevision++;gateGeneration=playGeneration;gateCatalogGeneration=generation;preferenceRevoked=true;gateEnabled=false;}pending=null;retry.clear();retirePool();catalog=new Catalog(Collections.emptyList());files=Collections.emptyList();pump();}
    /** Nonblocking revocation for a preference callback arriving off the owner thread. */
    public long revokeNow(){synchronized(gate){gateEnabled=false;preferenceRevoked=true;return ++gateRevision;}}
    public long revision(){synchronized(gate){return gateRevision;}}
    /** Editor boundaries revoke queued audio while retaining already loaded samples. */
    public void pauseInput(){checkOwner();inputActive=false;plays.clear();synchronized(gate){playGeneration++;gateGeneration=playGeneration;gateEnabled=false;}}
    public boolean resumeInput(long expectedRevision){
        checkOwner();synchronized(gate){
            if(gateRevision!=expectedRevision)return false;
            inputActive=true;playGeneration++;plays.clear();gateGeneration=playGeneration;gateEnabled=enabled && !preferenceRevoked;
        }return true;
    }
    private boolean isPreferenceRevoked(){synchronized(gate){return preferenceRevoked;}}
    private void enqueue(P pool,int id,int keyCode,float gain,float volume){
        if(plays.size()==MAX_PLAYS)plays.removeFirst();
        plays.addLast(new Play<>(playGeneration,owner.uptimeMillis(),pool,id,keyCode,gain,volume));
    }
    private void retirePool(){
        P old=currentPool;currentPool=null;cache.clear();early.clear();
        if(op!=null && op.kind!=Kind.RELEASE && (old==null || op.pool==old)){
            op.retired=true;
            if(op.kind==Kind.LOAD && op.returned){cancelTimeout(op);op=null;pendingRelease=old;}
        }else if(old!=null)pendingRelease=old;
    }
    private void pump(){
        checkOwner();if(op!=null)return;
        if(pendingRelease!=null && owner.uptimeMillis()>=releaseRetryAfter){P pool=pendingRelease;pendingRelease=null;start(Kind.RELEASE,pool,null,0);return;}
        if(!enabled || isPreferenceRevoked())return;
        while(!plays.isEmpty()){
            Play<P> event=plays.removeFirst();
            if(event.generation==playGeneration && owner.uptimeMillis()-event.created<=PLAY_TTL_MS && (pendingRelease==null || event.pool==null)){startPlay(event);return;}
        }
        if(pendingRelease!=null)return;
        String next=pending;pending=null;
        if(next==null){
            while(warmupCursor<files.size() && warmupCursor<WARMUP_SCAN && warmed<WARMUP){
                String p=files.get(warmupCursor++);
                if(!cache.containsKey(p) && retryDue(p)){next=p;warmed++;break;}
            }
        }
        if(next==null || cache.containsKey(next) || !retryDue(next))return;
        if(currentPool==null){
            pending=next;if(owner.uptimeMillis()<createRetryAfter)return;
            start(Kind.CREATE,null,null,0);return;
        }
        if(cache.size()>=CAPACITY){
            Map.Entry<String,Integer> oldest=cache.entrySet().iterator().next();
            int id=oldest.getValue();cache.remove(oldest.getKey());pending=next;start(Kind.UNLOAD,currentPool,null,id);return;
        }
        start(Kind.LOAD,currentPool,next,0);
    }
    private void startPlay(Play<P> event){
        Op<P> item=new Op<>(Kind.PLAY,event.generation,event.pool,null,event.id);item.play=event;op=item;
        try{
            worker.execute(()->{
                boolean claimed;
                synchronized(gate){claimed=gateEnabled && gateGeneration==event.generation && owner.uptimeMillis()-event.created<=PLAY_TTL_MS;}
                boolean ok=false;
                try{
                    if(claimed){
                        if(event.pool==null)backend.fallback(event.keyCode,event.systemVolume,event.created);
                        else if(!backend.play(event.pool,event.id,event.gain,event.created))backend.fallback(event.keyCode,event.systemVolume,event.created);
                    }
                    ok=true;
                }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
                catch(RuntimeException ignored){}
                finally{boolean result=ok;owner.post(()->onReturned(item,null,0,result));}
            });
        }catch(RuntimeException rejected){op=null;warn(Failure.EXECUTOR_REJECTED);}
    }
    private void start(Kind kind,P pool,String path,int id){
        Op<P> item=new Op<>(kind,generation,pool,path,id);item.catalog=catalog;op=item;
        try{
            worker.execute(()->{
                P made=null;int sample=0;boolean success=false;
                try{
                    boolean mayStart;
                    synchronized(gate){mayStart=!preferenceRevoked && gateCatalogGeneration==item.generation;}
                    if(!mayStart && (kind==Kind.CREATE || kind==Kind.LOAD)){ /* revoked before worker claimed it */ }
                    else if(kind==Kind.CREATE){made=backend.create((p,n,ok)->owner.post(()->onCallback(p,n,ok)));success=made!=null;}
                    else if(kind==Kind.LOAD){sample=backend.load(pool,item.catalog,path);success=sample>0;}
                    else if(kind==Kind.UNLOAD){backend.unload(pool,id);success=true;}
                    else {backend.release(pool);success=true;}
                }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
                catch(Exception ignored){}
                finally{P resultPool=made;int resultSample=sample;boolean ok=success;owner.post(()->onReturned(item,resultPool,resultSample,ok));}
            });
        }catch(RuntimeException rejected){
            op=null;
            if(kind==Kind.RELEASE){pendingRelease=pool;releaseRetryAfter=owner.uptimeMillis()+RETRY_MS;}
            else if(kind==Kind.CREATE)createRetryAfter=owner.uptimeMillis()+RETRY_MS;
            else if(kind==Kind.LOAD)markFailed(path);
            else {generation++;plays.clear();synchronized(gate){playGeneration++;gateGeneration=playGeneration;gateCatalogGeneration=generation;}currentPool=null;cache.clear();early.clear();pendingRelease=pool;}
            warn(Failure.EXECUTOR_REJECTED);
        }
    }
    private void onReturned(Op<P> item,P made,int sample,boolean ok){
        checkOwner();if(op!=item)return;item.returned=true;
        if(item.kind==Kind.CREATE){
            op=null;
            if(made!=null && (item.retired || item.generation!=generation || !enabled)){pendingRelease=made;}
            else if(ok){currentPool=made;}else{createRetryAfter=owner.uptimeMillis()+RETRY_MS;warn(Failure.POOL_CREATE);}
            pump();return;
        }
        if(item.kind==Kind.RELEASE){op=null;if(!ok){pendingRelease=item.pool;releaseRetryAfter=owner.uptimeMillis()+RETRY_MS;warn(Failure.POOL_RELEASE);pump();return;}releaseRetryAfter=0;pump();return;}
        if(item.kind==Kind.PLAY){
            op=null;
            if(item.retired && item.pool!=null)pendingRelease=item.pool;
            else if(!ok && item.pool!=null && item.pool==currentPool){generation++;plays.clear();synchronized(gate){playGeneration++;gateGeneration=playGeneration;gateCatalogGeneration=generation;}currentPool=null;cache.clear();early.clear();pendingRelease=item.pool;warn(Failure.POOL_PLAY);}
            else if(!ok)warn(Failure.SYSTEM_PLAY);
            pump();return;
        }
        if(item.retired || item.generation!=generation || item.pool!=currentPool){op=null;pendingRelease=item.pool;pump();return;}
        if(item.kind==Kind.UNLOAD){
            op=null;
            if(!ok){generation++;plays.clear();synchronized(gate){playGeneration++;gateGeneration=playGeneration;gateCatalogGeneration=generation;}currentPool=null;cache.clear();early.clear();pendingRelease=item.pool;warn(Failure.SAMPLE_UNLOAD);}
            pump();return;
        }
        if(!ok){op=null;markFailed(item.path);early.clear();warn(Failure.SAMPLE_LOAD);pump();return;}
        item.sample=sample;Boolean received=early.remove(sample);early.clear();
        if(received!=null){finish(item,received);return;}
        item.timeout=()->{
            item.timeout=null;
            if(op==item && item.returned){op=null;markFailed(item.path);warn(Failure.LOAD_TIMEOUT);start(Kind.UNLOAD,item.pool,null,item.sample);}
        };
        owner.postDelayed(item.timeout,CALLBACK_TIMEOUT_MS);
    }
    private void onCallback(P pool,int id,boolean success){
        checkOwner();Op<P> item=op;
        if(item==null || item.kind!=Kind.LOAD || item.pool!=pool || item.retired || item.generation!=generation || currentPool!=pool)return;
        if(!item.returned){if(early.size()<MAX_EARLY && !early.containsKey(id))early.put(id,success);return;}
        if(item.sample==id)finish(item,success);
    }
    private void cancelTimeout(Op<P> item){if(item.timeout!=null){owner.cancelDelayed(item.timeout);item.timeout=null;}}
    private void finish(Op<P> item,boolean success){
        if(op!=item)return;cancelTimeout(item);op=null;
        if(success){cache.put(item.path,item.sample);retry.remove(item.path);pump();}
        else{markFailed(item.path);warn(Failure.SAMPLE_DECODE);start(Kind.UNLOAD,item.pool,null,item.sample);}
    }
    private boolean retryDue(String path){Long after=retry.get(path);return after==null || owner.uptimeMillis()>=after;}
    private void markFailed(String path){retry.put(path,owner.uptimeMillis()+RETRY_MS);while(retry.size()>CAPACITY)retry.remove(retry.keySet().iterator().next());}
    private void warn(Failure reason){if(!warned){warned=true;backend.failed(reason, enabled && !isPreferenceRevoked());}}
    public int cachedCount(){checkOwner();return cache.size();}
    public boolean isCached(int index){checkOwner();return index>=0 && index<files.size() && cache.containsKey(files.get(index));}
}
