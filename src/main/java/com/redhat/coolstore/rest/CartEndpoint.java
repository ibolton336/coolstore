package com.redhat.coolstore.rest;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.redhat.coolstore.model.Product;
import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.model.ShoppingCartItem;
import com.redhat.coolstore.service.ShoppingCartService;

@ApplicationScoped
@Path("/cart")
public class CartEndpoint implements Serializable {

	private static final long serialVersionUID = -7227732980791688773L;

	@Inject
	private ShoppingCartService shoppingCartService;

	// Store carts keyed by cartId to avoid HTTP session dependency
	private Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();

	private ShoppingCart getOrCreateCart(String cartId) {
		return carts.computeIfAbsent(cartId, k -> new ShoppingCart());
	}

	@GET
	@Path("/{cartId}")
	@Produces(MediaType.APPLICATION_JSON)
	public ShoppingCart getCart(@PathParam("cartId") String cartId) {
		return getOrCreateCart(cartId);
	}

	@POST
	@Path("/checkout/{cartId}")
	@Produces(MediaType.APPLICATION_JSON)
	public ShoppingCart checkout(@PathParam("cartId") String cartId) {
		ShoppingCart cart = getOrCreateCart(cartId);
		shoppingCartService.priceShoppingCart(cart);
		// Process the order
		shoppingCartService.checkOutShoppingCart(cartId);
		// Clear the cart after checkout
		cart.resetShoppingCartItemList();
		shoppingCartService.priceShoppingCart(cart);
		return cart;
	}

	@POST
	@Path("/{cartId}/{itemId}/{quantity}")
	@Produces(MediaType.APPLICATION_JSON)
	public ShoppingCart add(@PathParam("cartId") String cartId,
							@PathParam("itemId") String itemId,
							@PathParam("quantity") int quantity) throws Exception {
		ShoppingCart cart = getOrCreateCart(cartId);

		Product product = shoppingCartService.getProduct(itemId);

		ShoppingCartItem sci = new ShoppingCartItem();
		sci.setProduct(product);
		sci.setQuantity(quantity);
		sci.setPrice(product.getPrice());
		cart.addShoppingCartItem(sci);

		try {
			shoppingCartService.priceShoppingCart(cart);
			cart.setShoppingCartItemList(dedupeCartItems(cart.getShoppingCartItemList()));
		} catch (Exception ex) {
			cart.removeShoppingCartItem(sci);
			throw ex;
		}

		return cart;
	}

	@POST
	@Path("/{cartId}/{tmpId}")
	@Produces(MediaType.APPLICATION_JSON)
	public ShoppingCart set(@PathParam("cartId") String cartId,
							@PathParam("tmpId") String tmpId) throws Exception {

		ShoppingCart cart = getOrCreateCart(cartId);
		ShoppingCart tmpCart = getOrCreateCart(tmpId);

		if (tmpCart != null) {
			cart.resetShoppingCartItemList();
			cart.setShoppingCartItemList(tmpCart.getShoppingCartItemList());
		}

		try {
			shoppingCartService.priceShoppingCart(cart);
			cart.setShoppingCartItemList(dedupeCartItems(cart.getShoppingCartItemList()));
		} catch (Exception ex) {
			throw ex;
		}

		return cart;
	}

	@DELETE
	@Path("/{cartId}/{itemId}/{quantity}")
	@Produces(MediaType.APPLICATION_JSON)
	public ShoppingCart delete(@PathParam("cartId") String cartId,
							   @PathParam("itemId") String itemId,
							   @PathParam("quantity") int quantity) throws Exception {

		List<ShoppingCartItem> toRemoveList = new ArrayList<>();

		ShoppingCart cart = getOrCreateCart(cartId);

		cart.getShoppingCartItemList().stream()
				.filter(sci -> sci.getProduct().getItemId().equals(itemId))
				.forEach(sci -> {
					if (quantity >= sci.getQuantity()) {
						toRemoveList.add(sci);
					} else {
						sci.setQuantity(sci.getQuantity() - quantity);
					}
				});

		toRemoveList.forEach(cart::removeShoppingCartItem);

		shoppingCartService.priceShoppingCart(cart);
		return cart;
	}



	private List<ShoppingCartItem> dedupeCartItems(List<ShoppingCartItem> cartItems) {
		List<ShoppingCartItem> result = new ArrayList<>();
		Map<String, Integer> quantityMap = new HashMap<>();
		for (ShoppingCartItem sci : cartItems) {
			if (quantityMap.containsKey(sci.getProduct().getItemId())) {
				quantityMap.put(sci.getProduct().getItemId(), quantityMap.get(sci.getProduct().getItemId()) + sci.getQuantity());
			} else {
				quantityMap.put(sci.getProduct().getItemId(), sci.getQuantity());
			}
		}

		for (String itemId : quantityMap.keySet()) {
			Product p = shoppingCartService.getProduct(itemId);
			ShoppingCartItem newItem = new ShoppingCartItem();
			newItem.setQuantity(quantityMap.get(itemId));
			newItem.setPrice(p.getPrice());
			newItem.setProduct(p);
			result.add(newItem);
		}
		return result;
	}
}
