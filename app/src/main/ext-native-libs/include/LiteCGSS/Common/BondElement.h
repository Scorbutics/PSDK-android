#ifndef CGSS_BOND_ELEMENT_H
#define CGSS_BOND_ELEMENT_H

#include "Bindable.h"

namespace cgss {
	template<class Value>
	class BondElement {
	private:
		friend class Bindable<Value>;
		Value m_value {};
		Bindable<Value>* m_target = nullptr;
	public:
		BondElement() = default;
		BondElement(const BondElement&) = delete;
		BondElement& operator=(const BondElement&) = delete;

		BondElement(BondElement&& bondElement) {
			operator=(std::move(bondElement));
		}

		BondElement& operator=(BondElement&& bondElement) {
			m_value = std::move(bondElement.m_value);
			bondElement.m_value = {};
			bind(bondElement.m_target);
			bondElement.m_target = nullptr;
			return *this;
		}

		const Value& getValue() const { return m_value; };

		void setValue(Value value) {
			m_value = std::move(value);
			if (m_target != nullptr) {
				m_target->updateFromValue(&m_value);
			}
		}

		virtual ~BondElement() {
			if (m_target != nullptr) {
				m_target->bindValue(nullptr);
			}
		}

		void bind(Bindable<Value>* target) {
			if (target != m_target) {
				auto lastLinkedElement = m_target;
				m_target = nullptr;
				if (lastLinkedElement != nullptr) {
					lastLinkedElement->bindValue(nullptr);
				}
				m_target = target;
				if (m_target != nullptr) {
					m_target->bindValue(this);
				}
			}
		}

		bool operator==(const BondElement<Value>& other) const {
			return m_value == other.m_value;
		}

		bool operator!=(const BondElement<Value>& other) const {
			return m_value != other.m_value;
		}

		const Value* operator->() const {
			return &m_value;
		}

		template <class Function>
		void edit(Function&& operate) {
			std::forward<Function>(operate)(m_value);
			if (m_target != nullptr) {
				m_target->updateFromValue(&m_value);
			}
		}
	protected:
		Value& value() { return m_value; }
	};
}

#endif
