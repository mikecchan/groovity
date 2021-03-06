/*******************************************************************************
 * © 2018 Disney | ABC Television Group
 *
 * Licensed under the Apache License, Version 2.0 (the "Apache License")
 * with the following modification; you may not use this file except in
 * compliance with the Apache License and the following modification to it:
 * Section 6. Trademarks. is deleted and replaced with:
 *
 * 6. Trademarks. This License does not grant permission to use the trade
 *     names, trademarks, service marks, or product names of the Licensor
 *     and its affiliates, except as required to comply with Section 4(c) of
 *     the License and to reproduce the content of the NOTICE file.
 *
 * You may obtain a copy of the Apache License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License with the above modification is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the Apache License for the specific
 * language governing permissions and limitations under the Apache License.
 *******************************************************************************/
package com.disney.groovity.tags;

import java.io.CharArrayWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.disney.groovity.Groovity;
import com.disney.groovity.GroovityConstants;
import com.disney.groovity.Taggable;
import com.disney.groovity.doc.Attr;
import com.disney.groovity.doc.Tag;
import com.disney.groovity.stats.GroovityStatistics;
import com.disney.groovity.stats.GroovityStatistics.Execution;
import com.disney.groovity.util.DeadlockFreeExecutor;
import com.disney.groovity.util.InterruptFactory;
import com.disney.groovity.util.ScriptHelper;

import groovy.lang.Binding;
import groovy.lang.Closure;
/**
 * Await and aggregate the results of all async tags in the body; also buffers and replays asynchronous output in order with synchronous output
 * <p>
 * param( <ul>	
 *	<li><i>var</i>: 
 *	the name of the variable to bind the results list,</li>	
 *	<li><i>timeout</i>: 
 *	number of seconds to wait before throwing an exception,</li>	
 *	<li><i>pool</i>: 
 *	max number of threads to occupy - allocates an isolated pool allocates an isolated pool unless already running in an isolated pool,</li>
 *	</ul>{
 *	<blockquote>// script containing asynchronous calls to wait upon</blockquote>
 * 	});
 * 
 * <p><b>returns</b> An ordered list of the return values of futures generated by the body
 *	
 *	<p>Sample
 *	<pre>
 *	await{async{3*4} async{5*6}}.sum(0)
 *	</pre>	
 * @author Alex Vigdor
 */
@Tag(
		info = "Await and aggregate the results of all async tags in the body; also buffers and replays asynchronous output in order with synchronous output",
		body = "script containing asynchronous calls to wait upon",
		sample="await{async{3*4} async{5*6}}.sum(0)",
		returns="An ordered list of the return values of futures generated by the body",
		attrs = { 
				@Attr(
						name = GroovityConstants.VAR, 
						info="the name of the variable to bind the results list",
						required = false
						),
				@Attr(
						name = GroovityConstants.TIMEOUT, 
						info="number of seconds to wait before throwing an exception",
						required = false
						),
				@Attr(
						name = GroovityConstants.POOL, 
						info="max number of threads to occupy - allocates an isolated pool unless already running in an isolated pool",
						required = false
						)		
		} 
		)
public class Await implements Taggable {
	final static String ASYNC_CONTEXT_BINDING = INTERNAL_BINDING_PREFIX.concat("Await$Context");
	
	InterruptFactory interruptFactory;

	public void setGroovity(Groovity groovity) {
		this.interruptFactory = groovity.getInterruptFactory();
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	@Override
	public Object tag(final Map attributes, final Closure body) throws Exception {
		final Integer timeoutSeconds = resolve(attributes, TIMEOUT, Integer.class);
		final ScriptHelper scriptHelper = getScriptHelper(body);
		final Binding binding = scriptHelper.getBinding();
		final Map variables = binding.getVariables();
		final AwaitContext awaitContext = AwaitContext.create(variables);
		DeadlockFreeExecutor createdThreadPool = null;
		DeadlockFreeExecutor oldThreadPool = null;
		if(!variables.containsKey(Async.EXECUTOR_BINDING)){
			Integer numThreads = resolve(attributes,POOL,Integer.class);
			if(numThreads!=null){
				createdThreadPool = new DeadlockFreeExecutor(interruptFactory, numThreads);
				oldThreadPool = (DeadlockFreeExecutor) variables.put(Async.EXECUTOR_BINDING, createdThreadPool);
			}
		}
		final Writer origOut = (Writer) variables.get(OUT);
		try{
			Collection<Object> resultsList ;
			final long timeoutTime = timeoutSeconds==null?-1:System.currentTimeMillis()+(timeoutSeconds*1000);
			Throwable error=null;
			try{
				body.call();
			}
			catch(Throwable e){
				error = e;
			}
			ArrayDeque<Future> futuresList=awaitContext.getFutures();
			resultsList = new ArrayList<>(futuresList.size());
			for(Future f : futuresList){
				if(error!=null){
					f.cancel(true);
					continue;
				}
				//flush any pending writers before waiting
				Optional<CharArrayWriter> ocw;
				while((ocw = awaitContext.nextFragmentWriter()).isPresent()){
					CharArrayWriter cw = ocw.get();
					if(cw.size()>0 && origOut!=null){
						cw.writeTo(origOut);
					}
				}
				if(timeoutTime==-1 || f.isDone()){
					try{
						resultsList.add(f.get());
					}
					catch(Throwable e){
						error = e;
					}
				}
				else{
					long timeoutDelta = timeoutTime - System.currentTimeMillis();
					if(timeoutDelta <= 0){
						error = new InterruptedException("Await reached timeout of "+timeoutSeconds);
						f.cancel(true);
					}
					else{
						try{
							resultsList.add(f.get(timeoutDelta,TimeUnit.MILLISECONDS));
						}
						catch(Throwable e){
							error = e;
						}
					}
				}
				if(error==null){
					//flush async buffer
					CharArrayWriter cw = awaitContext.nextFragmentWriter().get();
					if(cw.size()>0 && origOut!=null){
						cw.writeTo(origOut);
					}
				}
			}
			if(error !=null){
				if(error instanceof Exception){
					throw (Exception) error;
				}
				throw new ExecutionException(error); 
			}
			if(origOut!=null){
				final Object finalOut = variables.get(OUT);
				if(finalOut!=origOut){
					final CharArrayWriter cfo = ((CharArrayWriter)finalOut);
					try {
						if(cfo.size()>0){
							cfo.writeTo(origOut);
						}
					}
					finally {
						cfo.close();
					}
				}
			}
			final String var = resolve(attributes, VAR, String.class);
			if(var!=null && var.length()>0){
				variables.put(var, resultsList);
			}
			return resultsList;
		}
		finally{
			awaitContext.close(variables);
			if(origOut!=null){
				variables.put(OUT,origOut);
			}
			else{
				variables.remove(OUT);
			}
			if(createdThreadPool!=null){
				createdThreadPool.shutdown();
				try {
					if(!createdThreadPool.awaitTermination(60, TimeUnit.SECONDS)) {
						createdThreadPool.shutdownNow();
						createdThreadPool.awaitTermination(60, TimeUnit.SECONDS);
					}
				} catch (InterruptedException e) {
					createdThreadPool.shutdownNow();
				}
				if(oldThreadPool!=null){
					variables.put(Async.EXECUTOR_BINDING, oldThreadPool);
				}
				else{
					variables.remove(Async.EXECUTOR_BINDING);
				}
			}
		}
	}
	
	@SuppressWarnings({"rawtypes","unchecked"})
	public static class AwaitContext implements GroovityConstants {
		final ArrayDeque<Future> futures = new ArrayDeque<>();
		final ArrayDeque<Optional<CharArrayWriter>> fragmentWriters = new ArrayDeque<>();
		final Execution waitingExecution = GroovityStatistics.snapshot();
		AwaitContext parentContext = null;
		
		public final Execution getWaitingExecution(){
			return waitingExecution;
		}
		
		public final ArrayDeque<Future> getFutures(){
			return futures;
		}
		
		public final void close(final Map variables){
			if(parentContext!=null){
				variables.put(ASYNC_CONTEXT_BINDING, parentContext);
			}
			else{
				variables.remove(ASYNC_CONTEXT_BINDING);
			}
		}
		public void signalAsync(Map variables,CharArrayWriter asyncOut){
			if(fragmentWriters.isEmpty()){
				//first async call
				fragmentWriters.add(Optional.empty());
				fragmentWriters.add(Optional.of(asyncOut));
				variables.put(OUT,new CharArrayWriter());
			}
			else{
				final CharArrayWriter syncOut = (CharArrayWriter) variables.get(OUT);
				if(syncOut.size()>0){
					fragmentWriters.add(Optional.of(syncOut));
					variables.put(OUT,new CharArrayWriter());
				}
				fragmentWriters.add(Optional.empty());
				fragmentWriters.add(Optional.of(asyncOut));
			}
		}
		public void add(Future future){
			futures.add(future);
		}
		public Optional<CharArrayWriter> nextFragmentWriter(){
			return fragmentWriters.removeFirst();
		}
		public static final AwaitContext get(Map variables){
			return (AwaitContext) variables.get(ASYNC_CONTEXT_BINDING);
		}
		
		public static final AwaitContext create(Map variables){
			AwaitContext context = new AwaitContext();
			context.parentContext = (AwaitContext) variables.put(ASYNC_CONTEXT_BINDING, context);
			return context;
		}
		
	}

}
