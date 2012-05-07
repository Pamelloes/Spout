package org.spout.engine.renderer.shader.variables;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.spout.api.render.Texture;
import org.spout.engine.resources.ClientTexture;

public class TextureSamplerShaderVariable extends ShaderVariable {
	int textureID;
	int textureNumber;
	
	
	public TextureSamplerShaderVariable(int program, String name, Texture texture, int bindNum) {
		super(program, name);
		textureID = ((ClientTexture)texture).getTextureID();
		this.textureNumber = bindNum;
	}
	
	public void set(Texture texture){
		textureID = ((ClientTexture)texture).getTextureID();
	}

	@Override
	public void assign() {
		/*GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL20.glUniform1i(location, 0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureID);*/
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureNumber);
		GL20.glUniform1i(location, textureNumber);//0x01000000);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureID);
		
		IntBuffer val = ByteBuffer.allocateDirect(100).asIntBuffer();
		GL20.glGetUniform(program, location, val);
		System.out.println(String.format("Value is %d\nValue should be %d", swap(val.get()), textureNumber));
	}
	
	private int swap(int value) {
		int b1 = (value >> 0) & 0xff;
		int b2 = (value >> 8) & 0xff;
		int b3 = (value >> 16) & 0xff;
		int b4 = (value >> 24) & 0xff;

		return b1 << 24 | b2 << 16 | b3 << 8 | b4 << 0;
	}

}
