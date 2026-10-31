/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

/** Isolated draft: one active read and at most one latest request; no publication from worker. */
public final class LatestSoundConfigLoader<Q, R> {
    public interface Owner { boolean isOwner(); void post(Runnable task); }
    public interface Load<Q,R> { R read(Q request) throws Exception; }
    public interface Publish<Q,R> { void accept(Q request, R result, Exception failure); }
    private final Owner owner; private final Executor worker; private final Load<Q,R> load; private final Publish<Q,R> publish;
    private long generation; private Q pending; private boolean hasPending, running, closed;
    public LatestSoundConfigLoader(Owner owner,Executor worker,Load<Q,R> load,Publish<Q,R> publish){
        this.owner=Objects.requireNonNull(owner);this.worker=Objects.requireNonNull(worker);this.load=Objects.requireNonNull(load);this.publish=Objects.requireNonNull(publish);
    }
    private void checkOwner(){if(!owner.isOwner())throw new IllegalStateException("owner thread required");}
    public void request(Q request){checkOwner();if(closed)throw new IllegalStateException("closed");generation++;pending=Objects.requireNonNull(request);hasPending=true;pump();}
    /** Cancel pending publication while allowing later reuse; an active read remains the only worker. */
    public void invalidate(){checkOwner();generation++;pending=null;hasPending=false;}
    public void close(){checkOwner();closed=true;invalidate();}
    private void pump(){
        if(closed || running || !hasPending)return;
        final Q requested=pending; final long captured=generation; pending=null;hasPending=false;running=true;
        try{
            worker.execute(()->{
                R value=null;Exception failure=null;boolean completed=false;
                try{value=load.read(requested);completed=true;}
                catch(CancellationException cancelled){failure=cancelled;throw cancelled;}
                catch(Exception error){failure=error;completed=true;}
                finally{
                    R result=value;Exception error=failure;boolean report=completed;
                    owner.post(()->complete(requested,captured,result,error,report));
                }
            });
        }catch(RuntimeException rejected){
            running=false;
            // The worker never started; fail this request and let a later request retry.
            if(!closed && generation==captured)publish.accept(requested,null,rejected);
        }
    }
    private void complete(Q requested,long captured,R result,Exception error,boolean report){
        checkOwner();running=false;
        try{if(!closed && generation==captured && report && !(error instanceof CancellationException))publish.accept(requested,result,error);}
        finally{pump();}
    }
}
