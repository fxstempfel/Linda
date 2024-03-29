package linda.shm;

import linda.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/** Shared memory implementation of Linda. */
public class CentralizedLinda implements Linda {

	private List<Tuple> tupleSpace;
	// we use the queue interface to handle multiple callbacks over one key
	private Map<Tuple, Queue<Callback>> pendingReads;
	private Map<Tuple, Queue<Callback>> pendingTakes;
	private int nbPendingReads;
	private int nbPendingTakes;

    public CentralizedLinda() {
		tupleSpace = new ArrayList<Tuple>();
		pendingTakes = new HashMap<Tuple, Queue<Callback>>();
		pendingReads = new HashMap<Tuple, Queue<Callback>>();
		nbPendingReads = 0;
		nbPendingTakes = 0;
    }

	public void write(Tuple t) {
		boolean matchingTake = false;
		synchronized (this) {
			// Call the callbacks of the pending reads on the matching template
			for (Tuple template : pendingReads.keySet()) {
				if (t.matches(template)) {
					for (Callback c : pendingReads.remove(template)) {
						c.call(t.deepclone());
					}
				}
			}
			// Call the callback of the first occurence of the pending takes on
			// the key matching the template
			for (Tuple template : pendingTakes.keySet()) {
				if (t.matches(template)) {
					Queue<Callback> queue = pendingTakes.get(template); 
					// Remove the head of the queue
					queue.remove().call(t.deepclone());
					if (queue.isEmpty()) {
						// Remove the key if there is no more pending takes
						pendingTakes.remove(template);
					}
					matchingTake = true;
					break;
				}
			}
			// If there is no pending take over the tuple t, add it to the
			// tupleSpace
			if (!matchingTake) {
				tupleSpace.add(t.deepclone());
			}
		}
	}

	public Tuple take(Tuple template) {
		BlockingCallback callback = new BlockingCallback();
		nbPendingTakes++;
		// Call the eventRegister method, the callback will notify when a
		// matching tuple is found
		eventRegister(Linda.eventMode.TAKE, Linda.eventTiming.IMMEDIATE,
				template, callback);
		// Lock and wait until the call method of the callback notifies
		synchronized (callback) {
			while (callback.result==null) {
				try {
					callback.wait();
				} catch(Exception e) {
				}
			}
			nbPendingTakes--;
			return callback.result;
		}
	}

	public Tuple read(Tuple template) {
		BlockingCallback callback = new BlockingCallback();
		nbPendingReads++;
		// Call the eventRegister method, the callback will notify when a
		// matching tuple is found
		eventRegister(Linda.eventMode.READ, Linda.eventTiming.IMMEDIATE,
				template, callback);
		// Lock and wait until the call method of the callback notifies
		synchronized (callback) {
			while (callback.result==null) {
				try {
					callback.wait();
				} catch (Exception e) {
				}
			}
			nbPendingReads--;
			return callback.result;
		}
	}

	public Tuple tryTake(Tuple template) {
		Tuple t = null;
		// Lock to access the shared variables 
		synchronized (this) {
			for (Tuple t2 : tupleSpace) {
				if (t2.matches(template)) {
					t = t2;
					tupleSpace.remove(t2);
					break;
				}
			}
		}
		return t;
	}

	public Tuple tryRead(Tuple template) {
		Tuple t = null;
		// Lock to access the shared variables 
		synchronized (this) {
			for (Tuple t2 : tupleSpace) {
				if (t2.matches(template)) {
					t = t2;
					break;
				}
			}
		}
		return t;	
	}

	public List<Tuple> takeAll(Tuple template) {
		List<Tuple> listMatches = new ArrayList<Tuple>();
		Tuple t;
		// Call tryTake while matching tuples are found in the tupleSpace
		do {
			t = tryTake(template);
			if (t != null) {
				listMatches.add(t);
			}
		} while (t != null);

		return listMatches;
	}

	public List<Tuple> readAll(Tuple template) {
		List<Tuple> listMatches = new ArrayList<Tuple>();
		// Different than takeAll to prevent infinite loops : need to consider
		// each element of the tupleSpace only once. We therefore cannot use tryRead
		synchronized (this) {
			for (Tuple t2 : tupleSpace) {
				if (t2.matches(template)) {
					listMatches.add(t2);
				}
			}
		}
		return listMatches;
	}

	public void eventRegister(eventMode mode, eventTiming timing, Tuple template, Callback callback) {	
		Tuple t=null;
		boolean tupleFound = false;
		synchronized (this) {
			if (timing == eventTiming.IMMEDIATE) {
				if (mode == eventMode.TAKE) {
					t = tryTake(template);
				} else {
					// mode == READ
					t = tryRead(template);
				}
				if (t == null) {
					// If the tuple was not found in the tupleSpace, add it to
					// the pending operations
					if (mode == eventMode.TAKE) {
						addCallbackToQueue(pendingTakes, template, callback);
					} else {
						addCallbackToQueue(pendingReads, template, callback);
					}
				} else {
					tupleFound = true;
				}
			} else {
				// timing = FUTURE
				// Add the callback to the pending operations
				if (mode == eventMode.TAKE) {
					addCallbackToQueue(pendingTakes, template, callback);
				} else {
					// mode = READ
					addCallbackToQueue(pendingReads, template, callback);
				}

			}
		}
		// If the tuple was found in the tupleSpace, run the callback
		if (tupleFound) {
			callback.call(t); 
		}
	}

	private void addCallbackToQueue(Map<Tuple, Queue<Callback>> map, Tuple template, Callback callback) {
		// If the key already exists, add the callback to the
		// corresponding queue
		if (map.containsKey(template)) {
			map.get(template).add(callback);
		} else {
			// Else, create a new queue
			Queue<Callback> newQueue = new LinkedList<Callback>();
			newQueue.add(callback);
			map.put(template, newQueue);
		}
	}

    public void debug(String prefix) {
		// Print the tuples in the tupleSpace
		synchronized(this) {
			System.out.println(prefix + " TupleSpace : ");
			for (Tuple t : tupleSpace) {
				System.out.println(t);
			}

			//Print pendingReads
			System.out.println(prefix + " PendingReads : ");
			for (Tuple t : pendingReads.keySet()) {
				System.out.println(t + " (" + pendingReads.get(t).size() +")");
			}

			//Print pendingTakes
			System.out.println(prefix + " PendingTakes : ");
			for (Tuple t : pendingTakes.keySet()) {
				System.out.println(t + " (" + pendingTakes.get(t).size() +")");
			}

			System.out.println(prefix + " TupleSpace's size : " + getSizeTupleSpace());
			System.out.println(prefix + " Nb of active processes : " +
					getNbActiveProcesses());
			System.out.println(prefix + " \t including : " + nbPendingReads + 
					" reads");
			System.out.println(prefix + " \t and : " + nbPendingTakes + " takes");
		}
	}

	public int getSizeTupleSpace() {
		return tupleSpace.size();
	}

	public int getNbActiveProcesses() {
		return nbPendingReads + nbPendingTakes;
	}

	public int getNbPendingReads() {
		return nbPendingReads;
	}

	public int getNbPendingTakes() {
		return nbPendingTakes;
	}

	private class BlockingCallback implements Callback {
		
		Tuple result;
		
		private BlockingCallback() {}

		// This method is called when a matching tuple is found. It notifies to
		// wake up the method read or take which instantiated this callback
		public void call(Tuple t) {
			synchronized (this) {
				result = t;
				notify(); 
			}
		}
	}
}
