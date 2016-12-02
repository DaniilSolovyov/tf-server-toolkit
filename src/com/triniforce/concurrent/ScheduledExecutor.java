/*
 * Copyright(C) Triniforce
 * All Rights Reserved.
 *
 */ 
package com.triniforce.concurrent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.triniforce.server.srvapi.InitFinitTaskWrapper;
import com.triniforce.utils.ApiAlgs;
import com.triniforce.utils.ICheckInterrupted;


public class ScheduledExecutor extends ThreadPoolExecutor implements ScheduledExecutorService, Runnable{

    public static final int EMPTY_TASK_QUEUE_TIMEOUT_MS = 1000 * 60;

    BlockingQueue<Cmd> m_commandQueue = new LinkedBlockingQueue<Cmd>();
    Queue<ScheduledExecutorTask> m_taskQueue = new PriorityQueue<ScheduledExecutorTask>();
	private final Future<?> m_schedulerFuture;

    abstract class Cmd implements Runnable{
               
    }
    
    class CmdExceptionOccured extends Cmd{

        @Override
        public void run() {
        }
        
        CmdExceptionOccured(ScheduledExecutorTask task){

        }
    }
    
    class CmdScheduleTask extends Cmd{
        private final ScheduledExecutorTask m_task;

        CmdScheduleTask(ScheduledExecutorTask task){
            m_task = task;
        }

        @Override
        public void run() {
        	if(null != m_task && m_task.calcNextStart()){
        		m_taskQueue.add(m_task);
        	}
        }
    }
    
	class TaskWrapper implements Runnable {
		private final ScheduledExecutorTask m_t;

		public TaskWrapper(ScheduledExecutorTask t) {
			m_t = t;
		}

		@Override
		public void run() {
			try {
				m_t.run();

				//if exception occurs task won't be rescheduled
				
				Cmd c = new CmdScheduleTask(m_t);
				m_commandQueue.add(c);
				
			} catch (RuntimeException r) {
			    m_commandQueue.add(new CmdExceptionOccured(m_t));
				if(!ICheckInterrupted.Helper.isInterruptedException(r)){
					System.err.println(m_t.toString());
					r.printStackTrace();
				}
			}
		}
	}
    
    public static class MyThreadFactory implements ThreadFactory{

        AtomicInteger cnt = new AtomicInteger(0);
        private final int m_cntExecutor;
        
        MyThreadFactory(int cntExecutor){
            m_cntExecutor = cntExecutor;
            
        }
        
        @Override
        public Thread newThread(Runnable paramRunnable) {
            int curCnt = cnt.incrementAndGet();
            String sfx = 1 == curCnt? "scheduler": "" + curCnt;
            Thread t = new Thread(paramRunnable, "ScheduledExecutor_"+m_cntExecutor +"_" + sfx);
            return t;
        }
    }
    
    static AtomicInteger cntExecutors = new AtomicInteger(0);
    
    public ScheduledExecutor(int corePoolSize, int maxmimumPoolSize) {
        super(corePoolSize + 1, maxmimumPoolSize + 1, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new MyThreadFactory(cntExecutors.incrementAndGet()));
        m_schedulerFuture = submit(this);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable paramRunnable, long paramLong,
            TimeUnit paramTimeUnit) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> paramCallable,
            long paramLong, TimeUnit paramTimeUnit) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable paramRunnable,
            long paramLong1, long paramLong2, TimeUnit paramTimeUnit) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,
            long initialDelay, long delay, TimeUnit paramTimeUnit) {
        long initialDelayMs = paramTimeUnit.convert(initialDelay, TimeUnit.MILLISECONDS);
        long delayMs = paramTimeUnit.convert(delay, TimeUnit.MILLISECONDS);
        ScheduledExecutorTask t = new ScheduledExecutorTask(r, initialDelayMs, delayMs);
        m_commandQueue.offer(new CmdScheduleTask(t));
        return t;
    }

    boolean internal_doIteration(){
        try {
            ScheduledExecutorTask t = m_taskQueue.poll();
            long delayMs = (null == t) ? EMPTY_TASK_QUEUE_TIMEOUT_MS : t.getDelay(TimeUnit.MILLISECONDS); 
            if(delayMs > 0){
            	if(null != t && delayMs > 2 * t.getDelayMs()){
            		reportAboutTooBigDelay(t, delayMs);
            		
            		Cmd c = new CmdScheduleTask(t);
    				m_commandQueue.add(c);
            		return true;
            	}
            	else{
	                Cmd c = m_commandQueue.poll(delayMs, TimeUnit.MILLISECONDS);
	                if(null != c){
	                    //put task back
	                    if(null != t){
	                        m_taskQueue.add(t);
	                    }
	                    c.run();
	                    return true;
	                }
            	}
            }
            if(null == t){
                return true;
            }
            TaskWrapper tw = new  TaskWrapper(t);
            try{
                submit(tw);
            }catch(RejectedExecutionException re){
                //put task back
                m_taskQueue.add(t);
                //wait for any command and put it back
                Cmd c = m_commandQueue.poll(EMPTY_TASK_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if(null != c){
                    m_commandQueue.put(c);
                }
            }
        } catch (InterruptedException e) {
            return false;
        } catch (RuntimeException r){
            r.printStackTrace();
        }
        return true;
    }
    
    void reportAboutTooBigDelay(ScheduledExecutorTask t, long delayMs) {
    	ByteArrayOutputStream out = new ByteArrayOutputStream();
    	PrintStream print = new PrintStream(out);
    	SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS");
    	print.printf("Task \'%s\'have too big delay: %dms, time to start: %s", getTaskName(t), delayMs, df.format(new Date(t.m_nextStartMs)));
		ApiAlgs.getLog(this).info(out.toString());
		
	}

	private String getTaskName(ScheduledExecutorTask t) {
		Runnable rt = t.getRunnableTask();
		String res;
		if(rt instanceof InitFinitTaskWrapper){
			res = ((InitFinitTaskWrapper) rt).getTaskName();
		}
		else
			res = rt.getClass().getName();	
		return res;
	}

	@Override
    public void run() {
        while(internal_doIteration());
    }

	public Future<?> getSchedulerFuture() {
		return m_schedulerFuture;
	}    
}
