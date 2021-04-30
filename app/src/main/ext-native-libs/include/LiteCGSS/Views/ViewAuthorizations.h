#ifndef CGSS_VIEW_AUTHORIZATIONS_H
#define CGSS_VIEW_AUTHORIZATIONS_H
#include <type_traits>

namespace cgss {
	template <class Drawable>
	struct AuthorizedDrawableBox {};

	template <class ... Drawable>
	struct AuthorizedDrawableSet : public AuthorizedDrawableBox<Drawable>... {};

	template <class View>
	struct ViewAuthorizations {};

	template <class Drawable, class View>
	struct DrawableIsAuthorizedOnView : public std::is_base_of<AuthorizedDrawableBox<Drawable>, ViewAuthorizations<View>> {};
}

#endif