package at.andreasrohner.spartantimelapserec.rest;

/**
 * HTTP Reply codes
 */
public enum ReplyCode {

	/**
	 * Found
	 */
	FOUND(200, "Found"),

	/**
	 * Partial content (range request)
	 */
	PARTIAL_CONTENT(206, "Partial Content"),

	/**
	 * Forbidden
	 */
	FORBIDDEN(403, "Forbidden"),

	/**
	 * File was not found
	 */
	NOT_FOUND(404, "Not found"),

	/**
	 * Internal server error
	 */
	SERVER_ERROR(500, "Internal Server Error");

	/**
	 * HTTP Code
	 */
	public final int code;

	/**
	 * Result Text
	 */
	public final String text;

	/**
	 * Constructor
	 *
	 * @param code HTTP Code
	 * @param text Result Text
	 */
	private ReplyCode(int code, String text) {
		this.code = code;
		this.text = text;
	}
}
