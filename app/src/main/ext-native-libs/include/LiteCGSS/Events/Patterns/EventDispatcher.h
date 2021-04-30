#ifndef CGSS_EVENT_DISPATCHER_H
#define CGSS_EVENT_DISPATCHER_H

#include "Observable.h"

namespace cgss {
	template <class ...ET>
	class EventDispatcher : public Observable<ET>... {
	public:
		EventDispatcher() = default;
		EventDispatcher(const EventDispatcher<ET...>&) = delete;
		EventDispatcher(EventDispatcher&&) = default;

		template <class ...ET2>
		void addMultipleObservers(Observer<ET2>&... obs) {
			int _[] = { 0, (Observable<ET2>::addObserver(obs), 0)... };
			(void)_;
		}

		template <class ...ET2>
		void removeMultipleObservers(Observer<ET2>&... obs) {
			int _[] = { 0, (Observable<ET2>::removeObserver(obs), 0)... };
			(void)_;
		}

		virtual ~EventDispatcher() = default;
	};

}

#endif
