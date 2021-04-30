#ifndef CGSS_BINDABLE_H
#define CGSS_BINDABLE_H

#include <utility>

namespace cgss {
	template<class> class BondElement;

	template<class Value>
	class Bindable {
		friend class BondElement<Value>;
	public:
		Bindable() = default;
		Bindable(const Bindable&) = delete;
		Bindable& operator=(const Bindable&) = delete;

		Bindable(Bindable&& bindableElement) {
			m_destructing = true;
			operator=(std::move(bindableElement));
			m_destructing = false;
		}

		Bindable& operator=(Bindable&& bindableElement) {
			bindValue(bindableElement.m_target);
			bindableElement.m_target = nullptr;
			return *this;
		}

		virtual ~Bindable() {
			m_destructing = true;
			if (m_target != nullptr) {
				m_target->bind(nullptr);
			}
		}

		void bindValue(BondElement<Value>* element) {
			if (element != m_target) {
				auto lastLinkedValue = m_target;
				m_target = nullptr;
				if (lastLinkedValue != nullptr) {
					lastLinkedValue->bind(nullptr);
				}
				m_target = element;
				if (m_target != nullptr) {
					m_target->bind(this);
					if (!m_destructing) {
						updateFromValue(&m_target->getValue());
					}
				} else if (!m_destructing) {
					updateFromValue(nullptr);
				}
			}
		}

		void setValue(Value value) {
			if (!m_in) {
				m_in = true;
				if (m_target != nullptr) {
					m_target->setValue(std::move(value));
				} else {
					updateFromValue(&value);
				}
				m_in = false;
			}
		}

	protected:
		virtual void updateFromValue(const Value* value) = 0;

		const Value* getValue() const { return m_target != nullptr ? &m_target->getValue() : nullptr; }
		Value* value() { return m_target != nullptr ? &m_target->value() : nullptr; }

	private:
		BondElement<Value>* m_target = nullptr;
		bool m_destructing = false;
		bool m_in = false;
	};
}

#endif